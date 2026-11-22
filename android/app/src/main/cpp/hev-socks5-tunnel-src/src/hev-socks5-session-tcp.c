/*
 ============================================================================
 Name        : hev-socks5-session-tcp.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
 Description : Socks5 Session TCP
 ============================================================================
 */

#include <errno.h>
#include <string.h>
#include <time.h>

#include <lwip/tcp.h>

#include <hev-task.h>
#include <hev-task-io.h>
#include <hev-task-io-socket.h>
#include <hev-task-mutex.h>
#include <hev-memory-allocator.h>
#include <hev-socks5-misc.h>

#include "hev-utils.h"
#include "hev-config.h"
#include "hev-logger.h"
#include "hev-config-const.h"
#include "hev-socks5-tunnel.h"

#include "hev-socks5-session-tcp.h"

/*
 * Local Unbar addition (1.0.14): LOG-ONLY instrumentation state for the
 * diagnostic logs below. These variables only ever feed LOG_* arguments
 * (and gate whether a diagnostic line is emitted, i.e. the log
 * rate-limit); they never influence control flow or program behavior.
 * hev tasks run cooperatively on one thread, so no locking is needed.
 * The stall tracker is keyed by session pointer: a stall is the run of
 * consecutive EAGAINs for one session, reset by any successful write.
 */
static HevSocks5SessionTCP *splice_f_stall_self;
static long splice_f_stall_since_ms;
static long splice_f_stall_last_log_ms;
/* Local Unbar addition (1.0.38): the tcp_sent 1/sec rate-limit was
 * this file-global too; it is now per-session
 * (HevSocks5SessionTCP.sent_last_log_ms), so one busy session no
 * longer silences the diagnostic for every other session. */

/* Local Unbar addition (1.0.14): LOG ONLY — monotonic clock in ms, so
 * the log shows stall durations without wall-clock jumps. */
static long
diag_now_ms (void)
{
    struct timespec ts;

    if (clock_gettime (CLOCK_MONOTONIC, &ts) < 0)
        return 0;
    return (long) (ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static int
task_io_yielder (HevTaskYieldType type, void *data)
{
    HevSocks5Session *self = data;
    HevListNode *node;
    int res;

    res = hev_socks5_task_io_yielder (type, data);
    node = hev_socks5_session_get_node (self);
    hev_socks5_tunnel_update_session (node);

    return res;
}

static int
tcp_splice_f (HevSocks5SessionTCP *self)
{
    struct iovec iov[64];
    struct pbuf *p;
    int iovc = 0;
    int res = 1;

    if (self->queue) {
        for (p = self->queue; p && (iovc < 64); p = p->next, iovc++) {
            iov[iovc].iov_base = p->payload;
            iov[iovc].iov_len = p->len;
        }
    } else if (self->pcb_eof) {
        res = -1;
    } else {
        res = 0;
    }

    if (iovc) {
        ssize_t s = hev_task_io_writev (HEV_SOCKS5 (self)->fd, iov, iovc,
                                        hev_task_io_nowait, NULL);
        if (0 >= s) {
            int werrno = errno;
            if ((0 > s) && (EAGAIN == werrno)) {
                /* Local Unbar addition (1.0.14): LOG ONLY — the SOCKS
                 * write side stalled (EAGAIN). Rate-limited to one line
                 * per second: this fires once per wakeup while the
                 * queue is stalled. stalled_ms is the time since the
                 * first consecutive EAGAIN for this session. */
                long now_ms = diag_now_ms ();
                if (splice_f_stall_self != self) {
                    splice_f_stall_self = self;
                    splice_f_stall_since_ms = now_ms;
                }
                if (now_ms - splice_f_stall_last_log_ms >= 1000) {
                    splice_f_stall_last_log_ms = now_ms;
                    LOG_W ("%p socks5 session tcp splice_f EAGAIN "
                           "errno=%d queue=%u stalled_ms=%ld",
                           self, werrno,
                           self->queue ? self->queue->tot_len : 0,
                           now_ms - splice_f_stall_since_ms);
                }
                res = 0;
            } else {
                splice_f_stall_self = NULL;
                res = -1;
            }
        } else {
            splice_f_stall_self = NULL;
            hev_task_mutex_lock (self->mutex);
            while (s > 0) {
                u16_t n = (s > 0xffff) ? 0xffff : s;

                self->queue = pbuf_free_header (self->queue, n);
                if (self->pcb)
                    tcp_recved (self->pcb, n);
                s -= n;
            }
            hev_task_mutex_unlock (self->mutex);
            res = 1;
        }
    } else if (res < 0) {
        hev_task_io_socket_shutdown (HEV_SOCKS5 (self)->fd, SHUT_WR);
    }

    return res;
}

static int
tcp_splice_b (HevSocks5SessionTCP *self)
{
    struct iovec iov[2];
    int res = 1, iovc;
    /* Local Unbar addition (1.0.14): LOG ONLY — values captured for
     * the tcp_write failure log below; never affect control flow. */
    size_t log_accepted = 0;
    size_t log_rejected = 0;
    size_t log_use_before = 0;
    size_t log_rda_before = 0;
    size_t log_use_after = 0;
    size_t log_rda_after = 0;
    ssize_t log_len = 0;
    int log_sndbuf = 0;
    int log_sndqueuelen = 0;
    err_t log_first_err = ERR_OK;
    /* Local Unbar addition (1.0.38): ERR_MEM means "no send-buffer
     * space right now", not a dead session — see the write loop
     * below. log_blocked requests a 0 return (wait for ACK/window);
     * only log_fatal requests -1. */
    int log_blocked = 0;
    int log_fatal = 0;

    iovc = hev_ring_buffer_writing (self->buffer, iov);
    if (iovc) {
        ssize_t s = hev_task_io_readv (HEV_SOCKS5 (self)->fd, iov, iovc,
                                       hev_task_io_nowait, NULL);
        if (0 >= s) {
            if ((0 > s) && (EAGAIN == errno))
                res = 0;
            else
                res = -1;
        } else {
            hev_ring_buffer_write_finish (self->buffer, s);
        }
    } else {
        res = 0;
    }

    hev_task_mutex_lock (self->mutex);
    if (self->pcb) {
        iovc = hev_ring_buffer_reading (self->buffer, iov);
        if (iovc) {
            ssize_t s = 0;
            int i;
            log_use_before = self->buffer->use_size;
            log_rda_before = self->buffer->rda_size;
            for (i = 0; i < iovc; i++) {
                u8_t *ptr = iov[i].iov_base;
                size_t len = iov[i].iov_len;
                s += len;
                while (len > 0) {
                    u16_t n;
                    err_t werr;
                    size_t chunk = len;
                    int sndbuf;
                    /* Local Unbar addition (1.0.38): cap each
                     * tcp_write chunk to the free send buffer (and
                     * to an MSS multiple), as the lwIP tcp_write
                     * documentation prescribes: it returns ERR_MEM
                     * when the chunk exceeds tcp_sndbuf() or the
                     * send-queue limit, so offering up to 64 KiB
                     * at once only ever wasted the call. A chunk
                     * smaller than one MSS is offered as-is, so a
                     * window below one MSS still makes progress. */
                    sndbuf = tcp_sndbuf (self->pcb);
                    if (chunk > (size_t) sndbuf)
                        chunk = (size_t) sndbuf;
                    if (chunk > 0xffff)
                        chunk = 0xffff;
                    if ((self->pcb->mss > 0) &&
                        (chunk >= (size_t) self->pcb->mss))
                        chunk -= chunk % self->pcb->mss;
                    if (chunk == 0) {
                        /* Window full: wait for ACKs to open it
                         * instead of forcing an ERR_MEM. */
                        log_blocked = 1;
                        if (log_first_err == ERR_OK)
                            log_first_err = ERR_MEM;
                        goto write_done;
                    }
                    n = (u16_t) chunk;
                    werr = tcp_write (self->pcb, ptr, n, 0);
                    if (werr != ERR_OK) {
                        log_rejected += n;
                        if (log_first_err == ERR_OK)
                            log_first_err = werr;
                        if (werr == ERR_MEM)
                            log_blocked = 1;
                        else
                            log_fatal = 1;
                        /* Stop at the first refusal: bytes after
                         * it were never offered, stay in the ring
                         * (see read_finish below) and are re-offered
                         * on the next pass, once ACKs have opened
                         * the window. */
                        goto write_done;
                    }
                    log_accepted += n;
                    ptr += n;
                    len -= n;
                }
            }
        write_done:
            /* Local Unbar addition (1.0.38): read_finish only the
             * bytes tcp_write actually accepted. tcp_write is
             * called with flag 0, so lwIP only references the
             * ring memory — it must stay stable until ACKed, which
             * the ring's use_size (released by tcp_sent_handler)
             * guarantees. Bytes tcp_write refused were never seen
             * by lwIP, so finishing them would drop them from the
             * ring and punch a hole in the app-side stream that
             * TCP can never recover. */
            hev_ring_buffer_read_finish (self->buffer, log_accepted);
            log_use_after = self->buffer->use_size;
            log_rda_after = self->buffer->rda_size;
            log_len = s;
            if (!log_fatal) {
                err_t out_err = tcp_output (self->pcb);
                if (out_err == ERR_MEM) {
                    /* Queued data stays queued; it goes out once
                     * ACKs open the window, so wait like a write
                     * refusal instead of failing the session. */
                    log_blocked = 1;
                    if (log_first_err == ERR_OK)
                        log_first_err = ERR_MEM;
                } else if (out_err != ERR_OK) {
                    log_fatal = 1;
                    if (log_first_err == ERR_OK)
                        log_first_err = out_err;
                }
            }
            if (log_first_err != ERR_OK) {
                log_sndbuf = tcp_sndbuf (self->pcb);
                log_sndqueuelen = tcp_sndqueuelen (self->pcb);
            }
            if (log_fatal)
                res = -1;
            else if (log_blocked)
                res = 0;
            else
                res = 1;
        } else if (res < 0) {
            tcp_shutdown (self->pcb, 0, 1);
        }
    }
    hev_task_mutex_unlock (self->mutex);
    if (!self->pcb)
        res = -1;

    /* Local Unbar addition (1.0.14, 1.0.38): LOG ONLY — every failed
     * tcp_write()/tcp_output(), with the real error of that call,
     * the ring sizes before and after read_finish, accepted vs
     * rejected bytes, deferred bytes (offered but never attempted
     * because the loop stopped at the first refusal — still in the
     * ring), and res_b (the value this function returns). A wait
     * for window (ERR_MEM) is LOG_D: it is routine backpressure,
     * not a failure. Only a genuinely fatal error is LOG_W. */
    if (log_first_err != ERR_OK) {
        if (log_fatal) {
            LOG_W ("%p socks5 session tcp splice_b tcp_write failed "
                   "first_err=%d len=%zd sndbuf=%d sndqueuelen=%d "
                   "use_before=%zu rda_before=%zu use_after=%zu "
                   "rda_after=%zu accepted=%zu rejected=%zu "
                   "deferred=%zu res_b=%d",
                   self, log_first_err, log_len, log_sndbuf,
                   log_sndqueuelen, log_use_before, log_rda_before,
                   log_use_after, log_rda_after, log_accepted,
                   log_rejected,
                   (size_t) (log_len - (ssize_t) log_accepted -
                             (ssize_t) log_rejected),
                   res);
        } else {
            LOG_D ("%p socks5 session tcp splice_b tcp_write blocked "
                   "first_err=%d len=%zd sndbuf=%d sndqueuelen=%d "
                   "use_before=%zu rda_before=%zu use_after=%zu "
                   "rda_after=%zu accepted=%zu rejected=%zu "
                   "deferred=%zu res_b=%d",
                   self, log_first_err, log_len, log_sndbuf,
                   log_sndqueuelen, log_use_before, log_rda_before,
                   log_use_after, log_rda_after, log_accepted,
                   log_rejected,
                   (size_t) (log_len - (ssize_t) log_accepted -
                             (ssize_t) log_rejected),
                   res);
        }
    }

    return res;
}

static err_t
tcp_recv_handler (void *arg, struct tcp_pcb *pcb, struct pbuf *p, err_t err)
{
    HevSocks5SessionTCP *self = arg;

    if (p) {
        if (self->queue)
            pbuf_cat (self->queue, p);
        else
            self->queue = p;
    } else {
        self->pcb_eof = 1;
    }

    hev_task_wakeup (self->data.task);
    return ERR_OK;
}

static err_t
tcp_sent_handler (void *arg, struct tcp_pcb *pcb, u16_t len)
{
    HevSocks5SessionTCP *self = arg;

    if (self->buffer) {
        /* Local Unbar addition (1.0.14, 1.0.38): LOG ONLY — acked
         * bytes and ring use_size before/after read_release. This
         * fires per ACK, so it is gated to a real backlog
         * (use_size >= 8 KiB) and rate-limited to one line per
         * second, per session (1.0.38: the limit was one
         * file-global shared by all sessions). The line is LOG_D:
         * the advertised window is 131 KiB, so any active download
         * has >= 8 KiB un-ACKed and this fired constantly — noise,
         * not a stall signal. WARN is kept only when the backlog
         * approaches TCP_SND_BUF, with tcp_sndbuf() logged too.
         * (At the default 64 KiB ring the backlog can never reach
         * TCP_SND_BUF, so the WARN is unreachable in practice and
         * the line is effectively LOG_D.) */
        size_t use_before = self->buffer->use_size;
        size_t rel = len;
        int sndbuf;

        /* Local Unbar addition (1.0.38): clamp the release to the
         * bytes actually outstanding — an ACK for more than
         * use_size must not underflow use_size (size_t wrap) and
         * corrupt the ring accounting. */
        if (rel > use_before)
            rel = use_before;
        hev_ring_buffer_read_release (self->buffer, rel);
        if ((len > 0) && (use_before >= 8192)) {
            long now_ms = diag_now_ms ();
            if (now_ms - self->sent_last_log_ms >= 1000) {
                self->sent_last_log_ms = now_ms;
                sndbuf = tcp_sndbuf (pcb);
                if (use_before >= (size_t) (TCP_SND_BUF - TCP_MSS))
                    LOG_W ("%p socks5 session tcp sent acked=%u "
                           "use_before=%zu use_after=%zu sndbuf=%d",
                           self, len, use_before,
                           self->buffer->use_size, sndbuf);
                else
                    LOG_D ("%p socks5 session tcp sent acked=%u "
                           "use_before=%zu use_after=%zu sndbuf=%d",
                           self, len, use_before,
                           self->buffer->use_size, sndbuf);
            }
        }
    }
    hev_task_wakeup (self->data.task);

    return ERR_OK;
}

static void
tcp_err_handler (void *arg, err_t err)
{
    HevSocks5SessionTCP *self = arg;

    self->pcb = NULL;
    hev_socks5_session_terminate (HEV_SOCKS5_SESSION (self));
}

HevSocks5SessionTCP *
hev_socks5_session_tcp_new (struct tcp_pcb *pcb, HevTaskMutex *mutex)
{
    HevSocks5SessionTCP *self;
    int res;

    self = hev_malloc0 (sizeof (HevSocks5SessionTCP));
    if (!self)
        return NULL;

    res = hev_socks5_session_tcp_construct (self, pcb, mutex);
    if (res < 0) {
        hev_free (self);
        return NULL;
    }

    LOG_D ("%p socks5 session tcp new", self);

    return self;
}

/*
 * Returns -1 when the session is done, otherwise it is idle: 0 if nothing was
 * transferred, 1 if something was.
 */
static __attribute__ ((noinline)) int
tcp_splice_active (HevSocks5Session *base, int size, int *res_f, int *res_b)
{
    HevSocks5SessionTCP *self = HEV_SOCKS5_SESSION_TCP (base);
    int busy = 0;

    self->buffer = hev_ring_buffer_alloca (size);
    if (!self->buffer)
        return -1;

    for (;;) {
        HevTaskYieldType type;

        if (*res_f >= 0)
            *res_f = tcp_splice_f (self);
        if (*res_b >= 0)
            *res_b = tcp_splice_b (self);

        if (*res_f > 0 || *res_b > 0)
            type = HEV_TASK_YIELD;
        else if ((*res_f & *res_b) == 0)
            type = HEV_TASK_WAITIO;
        else
            break;

        /* Nothing in flight, leave the frame to let the stack be released. */
        if (type == HEV_TASK_WAITIO && self->pcb &&
            hev_ring_buffer_get_use_size (self->buffer) == 0) {
            self->buffer = NULL;
            return busy;
        }

        if (task_io_yielder (type, base) < 0)
            break;
        busy = 1;
    }

    while (self->pcb) {
        if (hev_ring_buffer_get_use_size (self->buffer) == 0)
            break;

        if (task_io_yielder (HEV_TASK_WAITIO, base) < 0)
            break;
    }

    self->buffer = NULL;
    return -1;
}

static void
hev_socks5_session_tcp_splice (HevSocks5Session *base)
{
    HevSocks5SessionTCP *self = HEV_SOCKS5_SESSION_TCP (base);
    int tcp_buffer_size;
    int released = 0;
    int res_f = 1;
    int res_b = 1;
    int res;

    LOG_D ("%p socks5 session tcp splice", self);

    if (!self->pcb)
        return;

    /* Hold whole (super) segments, a full buffer is sent in full packets. */
    tcp_buffer_size = hev_config_get_misc_tcp_buffer_size ();
    if (tcp_buffer_size > self->pcb->mss)
        tcp_buffer_size -= tcp_buffer_size % self->pcb->mss;

    for (;;) {
        res = tcp_splice_active (base, tcp_buffer_size, &res_f, &res_b);
        if (res < 0)
            break;
        if (res > 0)
            released = 0;

        released = hev_socks5_session_idle_wait (base, released);
        if (released < 0)
            break;
    }
}

static HevTask *
hev_socks5_session_tcp_get_task (HevSocks5Session *base)
{
    HevSocks5SessionTCP *self = HEV_SOCKS5_SESSION_TCP (base);

    return self->data.task;
}

static void
hev_socks5_session_tcp_set_task (HevSocks5Session *base, HevTask *task)
{
    HevSocks5SessionTCP *self = HEV_SOCKS5_SESSION_TCP (base);

    self->data.task = task;
}

static HevListNode *
hev_socks5_session_tcp_get_node (HevSocks5Session *base)
{
    HevSocks5SessionTCP *self = HEV_SOCKS5_SESSION_TCP (base);

    return &self->data.node;
}

int
hev_socks5_session_tcp_construct (HevSocks5SessionTCP *self,
                                  struct tcp_pcb *pcb, HevTaskMutex *mutex)
{
    HevSocks5Addr addr;
    int res;

    res = hev_socks5_addr_from_lwip (&addr, &pcb->local_ip, pcb->local_port);
    if (res < 0)
        return -1;

    res = hev_socks5_client_tcp_construct (&self->base, &addr);
    if (res < 0)
        return -1;

    LOG_D ("%p socks5 session tcp construct", self);

    HEV_OBJECT (self)->klass = HEV_SOCKS5_SESSION_TCP_TYPE;

    tcp_arg (pcb, self);
    tcp_recv (pcb, tcp_recv_handler);
    tcp_sent (pcb, tcp_sent_handler);
    tcp_err (pcb, tcp_err_handler);

    self->pcb = pcb;
    self->mutex = mutex;
    self->data.self = self;

    return 0;
}

void
hev_socks5_session_tcp_destruct (HevObject *base)
{
    HevSocks5SessionTCP *self = HEV_SOCKS5_SESSION_TCP (base);

    LOG_D ("%p socks5 session tcp destruct", self);

    hev_task_mutex_lock (self->mutex);
    if (self->pcb) {
        tcp_recv (self->pcb, NULL);
        tcp_sent (self->pcb, NULL);
        tcp_err (self->pcb, NULL);
        tcp_abort (self->pcb);
    }

    if (self->queue)
        pbuf_free (self->queue);
    hev_task_mutex_unlock (self->mutex);

    HEV_SOCKS5_CLIENT_TCP_TYPE->destruct (base);
}

static void *
hev_socks5_session_tcp_iface (HevObject *base, void *type)
{
    if (type == HEV_SOCKS5_SESSION_TYPE) {
        HevSocks5SessionTCPClass *klass = HEV_OBJECT_GET_CLASS (base);
        return &klass->session;
    }

    return HEV_SOCKS5_CLIENT_TCP_TYPE->iface (base, type);
}

HevObjectClass *
hev_socks5_session_tcp_class (void)
{
    static HevSocks5SessionTCPClass klass;
    HevSocks5SessionTCPClass *kptr = &klass;
    HevObjectClass *okptr = HEV_OBJECT_CLASS (kptr);

    if (!okptr->name) {
        HevSocks5Class *skptr;
        HevSocks5SessionIface *siptr;
        void *ptr;

        ptr = HEV_SOCKS5_CLIENT_TCP_TYPE;
        memcpy (kptr, ptr, sizeof (HevSocks5ClientTCPClass));

        okptr->name = "HevSocks5SessionTCP";
        okptr->destruct = hev_socks5_session_tcp_destruct;
        okptr->iface = hev_socks5_session_tcp_iface;

        skptr = HEV_SOCKS5_CLASS (kptr);
        skptr->binder = hev_socks5_session_bind;

        siptr = &kptr->session;
        siptr->splicer = hev_socks5_session_tcp_splice;
        siptr->get_task = hev_socks5_session_tcp_get_task;
        siptr->set_task = hev_socks5_session_tcp_set_task;
        siptr->get_node = hev_socks5_session_tcp_get_node;
    }

    return okptr;
}
