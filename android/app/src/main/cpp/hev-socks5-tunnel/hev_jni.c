/*
 * JNI wrapper for the vendored tunnel bridge, release 2.20.0 (MIT — see the vendored tree's LICENSE file).
 *
 * The tunnel runs on its own pthread: hev_socks5_tunnel_main_from_str
 * blocks until hev_socks5_tunnel_quit() is called, so start/stop are
 * asynchronous from the Kotlin side. The config is a YAML string
 * (see HevSocks5Tunnel.kt) and the TUN file descriptor comes from
 * Android's VpnService.Builder.establish().
 *
 * Upstream ships its own JNI binding (src/hev-jni.c) bound to the fixed
 * class hev.htproxy.TProxyService and taking a config file path; this
 * wrapper is used instead so the config can be passed as a string and
 * the class can live in Unbar's package. Upstream's src/hev-jni.c is
 * therefore excluded from the build (see Android.mk).
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <stdatomic.h>
#include <unistd.h>
#include <android/log.h>

#include "../hev-socks5-tunnel-src/src/hev-main.h"

#define LOG_TAG "HevTunnel"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Local Unbar addition (1.0.38): how long nativeStop waits for the
 * tunnel thread to exit before detaching it and returning. */
#define TUNNEL_STOP_TIMEOUT_MS 3000

static pthread_t tunnel_thread;
/* Local Unbar addition (1.0.38): atomic start/stop guard — a plain
 * volatile int let two concurrent starts both pass the check and
 * spawn two tunnel threads on the same globals. Only a
 * compare-exchange in nativeStart may set this to 1; the tunnel
 * thread itself clears it on exit. */
static atomic_int tunnel_running = 0;
static char *config_content = NULL;
static int tun_fd_global = -1;

/* Local Unbar addition (1.0.38): mask the SOCKS password in a config
 * dump before logging — the config carries the password in
 * cleartext, and the log line must never print it. Returns a fresh
 * copy with the password value replaced by '***' (or a plain copy
 * when there is no password line). */
static char *mask_config_password(const char *config) {
    const char *key = "password:";
    const char *p = strstr(config, key);
    const char *val, *eol;
    char *masked;
    size_t prefix_len, suffix_len;

    if (!p)
        return strdup(config);

    val = p + strlen(key);
    while (*val == ' ' || *val == '\t')
        val++;
    eol = strchr(val, '\n');
    if (!eol)
        eol = val + strlen(val);

    prefix_len = (size_t)(val - config);
    suffix_len = strlen(eol);
    masked = malloc(prefix_len + 3 + suffix_len + 1);
    if (!masked)
        return strdup(config);
    memcpy(masked, config, prefix_len);
    memcpy(masked + prefix_len, "***", 3);
    memcpy(masked + prefix_len + 3, eol, suffix_len + 1);
    return masked;
}

static void *tunnel_thread_func(void *arg) {
    (void)arg;
    LOGI("Tunnel thread started");

    int result = hev_socks5_tunnel_main_from_str(
        (const unsigned char *)config_content,
        strlen(config_content),
        tun_fd_global
    );

    LOGI("Tunnel thread exited with result: %d", result);
    atomic_store(&tunnel_running, 0);

    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_breakthebarriers_unbar_tunnel_HevSocks5Tunnel_nativeStart(
    JNIEnv *env,
    jclass clazz,
    jstring config,
    jint tun_fd
) {
    (void)clazz;
    int expected = 0;

    /* Local Unbar addition (1.0.38): atomic compare-exchange — exactly
     * one starter wins. The flag is set here, before pthread_create,
     * so a concurrent start cannot slip in while the thread is
     * being created; every failure path below clears it again. */
    if (!atomic_compare_exchange_strong(&tunnel_running, &expected, 1)) {
        LOGE("Tunnel already running");
        return -1;
    }

    const char *config_str = (*env)->GetStringUTFChars(env, config, NULL);
    if (!config_str) {
        LOGE("Failed to get config string");
        atomic_store(&tunnel_running, 0);
        return -1;
    }

    if (config_content) {
        free(config_content);
    }

    config_content = strdup(config_str);
    (*env)->ReleaseStringUTFChars(env, config, config_str);

    if (!config_content) {
        LOGE("Failed to allocate config memory");
        atomic_store(&tunnel_running, 0);
        return -1;
    }

    tun_fd_global = tun_fd;

    LOGI("Starting tunnel with fd=%d", tun_fd);
    /* Local Unbar addition (1.0.38): log the config with the SOCKS
     * password masked — never in cleartext. */
    {
        char *masked = mask_config_password(config_content);
        LOGI("Config:\n%s", masked ? masked : "(unavailable)");
        free(masked);
    }

    int ret = pthread_create(&tunnel_thread, NULL, tunnel_thread_func, NULL);
    if (ret != 0) {
        LOGE("Failed to create tunnel thread: %d", ret);
        atomic_store(&tunnel_running, 0);
        free(config_content);
        config_content = NULL;
        return -1;
    }

    LOGI("Tunnel started successfully");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_breakthebarriers_unbar_tunnel_HevSocks5Tunnel_nativeStop(
    JNIEnv *env,
    jclass clazz
) {
    (void)env;
    (void)clazz;
    if (!atomic_load(&tunnel_running)) {
        LOGI("Tunnel not running");
        return;
    }

    LOGI("Stopping tunnel...");
    hev_socks5_tunnel_quit();

    /* Local Unbar addition (1.0.38): time-bounded stop — the old
     * code did an unbounded pthread_join here, so a stuck tunnel
     * thread hung the caller forever. Wait up to
     * TUNNEL_STOP_TIMEOUT_MS for the thread to clear
     * tunnel_running, then join it. The thread handle is captured
     * up front: a new start can only win its compare-exchange after
     * the old thread clears the flag, and joining the captured
     * handle stays correct even if tunnel_thread was overwritten
     * by then. */
    pthread_t thread = tunnel_thread;
    {
        int waited_ms = 0;
        while (waited_ms < TUNNEL_STOP_TIMEOUT_MS &&
               atomic_load(&tunnel_running)) {
            usleep(100 * 1000);
            waited_ms += 100;
        }
    }

    if (atomic_load(&tunnel_running)) {
        /* Still running after the timeout: detach and return instead
         * of hanging. The thread clears tunnel_running when it
         * finally exits, and the next start frees/replaces
         * config_content only after that flag clears, so the
         * detached thread never races a new config. */
        LOGE("Tunnel thread did not stop in %dms, detaching",
             TUNNEL_STOP_TIMEOUT_MS);
        pthread_detach(thread);
        tun_fd_global = -1;
        return;
    }

    pthread_join(thread, NULL);

    if (config_content) {
        free(config_content);
        config_content = NULL;
    }

    tun_fd_global = -1;
    LOGI("Tunnel stopped");
}

JNIEXPORT void JNICALL
Java_com_breakthebarriers_unbar_tunnel_HevSocks5Tunnel_nativeSetRejectQuic(
    JNIEnv *env,
    jclass clazz,
    jboolean enabled
) {
    (void)env;
    (void)clazz;
    hev_socks5_tunnel_set_reject_quic(enabled ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_breakthebarriers_unbar_tunnel_HevSocks5Tunnel_nativeSetRejectNonDnsUdp(
    JNIEnv *env,
    jclass clazz,
    jboolean enabled
) {
    (void)env;
    (void)clazz;
    hev_socks5_tunnel_set_reject_non_dns_udp(enabled ? 1 : 0);
}

JNIEXPORT jboolean JNICALL
Java_com_breakthebarriers_unbar_tunnel_HevSocks5Tunnel_nativeIsRunning(
    JNIEnv *env,
    jclass clazz
) {
    (void)env;
    (void)clazz;
    return atomic_load(&tunnel_running) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_com_breakthebarriers_unbar_tunnel_HevSocks5Tunnel_nativeGetStats(
    JNIEnv *env,
    jclass clazz
) {
    (void)clazz;
    size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;

    if (atomic_load(&tunnel_running)) {
        hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
    }

    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result) {
        jlong stats[4] = {
            (jlong)tx_packets,
            (jlong)tx_bytes,
            (jlong)rx_packets,
            (jlong)rx_bytes
        };
        (*env)->SetLongArrayRegion(env, result, 0, 4, stats);
    }

    return result;
}
