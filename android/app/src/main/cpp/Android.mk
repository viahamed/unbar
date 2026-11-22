# Android.mk for the tunnel bridge 2.20.0 (Unbar).
#
# Vendored, tag 2.20.0 (MIT — see the vendored tree's LICENSE file).
# The vendored tree carries ONE local addition (1.0.13): answering QUIC
# and non-DNS UDP with ICMP Port Unreachable in src/hev-socks5-tunnel.c
# (set_reject_quic / set_reject_non_dns_udp); everything else is exactly
# upstream (the GSO removal and netif->mtu fixes were reverted in 1.0.10).
#
# Builds the tunnel-bridge shared library plus a small JNI wrapper
# (hev-tunnel-jni) that exposes start/stop/stats to Kotlin. Uses ndk-build;
# see Application.mk for the ABIs and platform level.
#
# Upstream's own JNI binding (src/hev-jni.c) is EXCLUDED: it binds to the
# fixed class hev.htproxy.TProxyService and takes a config file path.
# Unbar uses its own wrapper (hev-socks5-tunnel/hev_jni.c) that takes the
# config as a string and lives in com.breakthebarriers.unbar.tunnel.

MY_LOCAL_PATH := $(call my-dir)
HEV_PATH := $(MY_LOCAL_PATH)/hev-socks5-tunnel-src

# Third-party static libraries (from upstream's submodules).
include $(HEV_PATH)/third-part/yaml/Android.mk
include $(HEV_PATH)/third-part/lwip/Android.mk
ENABLE_MEMALLOC_SLICE := 0
include $(HEV_PATH)/third-part/hev-task-system/Android.mk

# Collect all tunnel-bridge C sources (src/ recursively, including
# src/core/ and src/misc/), excluding upstream's own JNI binding.
rwildcard=$(foreach d,$(wildcard $1*), \
          $(call rwildcard,$d/,$2) \
          $(filter $(subst *,%,$2),$d))
HEV_SRC_DIR := $(HEV_PATH)/src
HEV_SRCFILES := $(call rwildcard,$(HEV_SRC_DIR)/,*.c *.S)
HEV_SRCFILES := $(filter-out $(HEV_SRC_DIR)/hev-jni.c,$(HEV_SRCFILES))
HEV_SRCFILES := $(patsubst $(HEV_SRC_DIR)/%,src/%,$(HEV_SRCFILES))
HEV_INCLUDES := \
    $(HEV_PATH)/src \
    $(HEV_PATH)/src/misc \
    $(HEV_PATH)/src/core/include \
    $(HEV_PATH)/third-part/yaml/include \
    $(HEV_PATH)/third-part/lwip/src/include \
    $(HEV_PATH)/third-part/lwip/src/ports/include \
    $(HEV_PATH)/third-part/hev-task-system/include

# tunnel-bridge shared library
# (LOCAL_PATH must be the vendored tree root so the relative
# LOCAL_SRC_FILES paths resolve inside it, not inside whichever
# third-part module was included last.)
LOCAL_PATH := $(HEV_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := hev-socks5-tunnel
LOCAL_SRC_FILES := $(HEV_SRCFILES)
LOCAL_C_INCLUDES := $(HEV_INCLUDES)
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY
LOCAL_CFLAGS += -fno-stack-clash-protection
# 1.0.13: drop unused sections so the .so is smaller.
LOCAL_CFLAGS += -ffunction-sections -fdata-sections
LOCAL_CFLAGS += -DCOMMIT_ID=\"2.20.0-unbar\"
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_LDFLAGS += -Wl,-z,common-page-size=16384
# 1.0.13: strip symbols and drop unused sections (smaller .so).
LOCAL_LDFLAGS += -Wl,--gc-sections -s
include $(BUILD_SHARED_LIBRARY)

# JNI wrapper (Unbar's own, see hev-socks5-tunnel/hev_jni.c)
LOCAL_PATH := $(MY_LOCAL_PATH)
include $(CLEAR_VARS)

LOCAL_MODULE := hev-tunnel-jni
LOCAL_SRC_FILES := hev-socks5-tunnel/hev_jni.c
LOCAL_C_INCLUDES := $(HEV_PATH)/src
LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_LDFLAGS += -Wl,-z,common-page-size=16384
LOCAL_LDFLAGS += -Wl,--gc-sections -s

include $(BUILD_SHARED_LIBRARY)
