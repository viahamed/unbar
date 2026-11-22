# Application.mk for the tunnel bridge 2.20.0 (Unbar).
#
# ABIs match the Go core (.aar built for arm64,arm,amd64) and the app's
# supported ABIs. Platform android-21 matches minSdk 21 so the library
# never calls an API that an old device lacks. STL none: the bridge is pure C
# and needs no C++ runtime. 16 KB page sizes are supported (modern
# Android devices), matching upstream's Application.mk.

APP_ABI := arm64-v8a armeabi-v7a x86_64
APP_PLATFORM := android-21
APP_OPTIM := release
APP_STL := none
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

# Reproducible builds: strip the checkout location from __FILE__ strings
# (lwIP asserts) and debug info.
UNBAR_CPP_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
APP_CFLAGS += -ffile-prefix-map=$(UNBAR_CPP_DIR)=. -ffile-prefix-map=$(HOME)=~
