LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := iperf2
LOCAL_MODULE_TAGS := optional
LOCAL_CFLAGS := -DHAVE_CONFIG_H -O2 -g -UAF_INET6 -w -Wno-error=format-security
LOCAL_CPPFLAGS := -DHAVE_CONFIG_H -O2 -g -UAF_INET6 -w -Wno-error=format-security
LOCAL_LDFLAGS := -fPIE -pie
LOCAL_MODULE_PATH := $(TARGET_OUT_OPTIONAL_EXECUTABLES)

LOCAL_SRC_FILES := \
    compat/Thread.c \
    compat/error.c \
    compat/delay.c \
    compat/gettimeofday.c \
    compat/gettcpinfo.c \
    compat/inet_ntop.c \
    compat/inet_pton.c \
    compat/signal.c \
    compat/snprintf.c \
    compat/string.c \
    src/Client.cpp \
    src/Extractor.c \
    src/isochronous.cpp \
    src/Launch.cpp \
    src/active_hosts.cpp \
    src/Listener.cpp \
    src/Locale.c \
    src/PerfSocket.cpp \
    src/Reporter.c \
    src/Reports.c \
    src/ReportOutputs.c \
    src/Server.cpp \
    src/Settings.cpp \
    src/SocketAddr.c \
    src/gnu_getopt.c \
    src/gnu_getopt_long.c \
    src/histogram.c \
    src/main.cpp \
    src/service.c \
    src/socket_io.c \
    src/stdio.c \
    src/packet_ring.c \
    src/tcp_window_size.c \
    src/pdfs.c \
    src/dscp.c \
    src/iperf_formattime.c \
    src/iperf_multicast_api.c

LOCAL_C_INCLUDES += \
    $(LOCAL_PATH) \
    $(LOCAL_PATH)/include \
    $(LOCAL_PATH)/compat \
    $(LOCAL_PATH)/src

LOCAL_DISABLE_FORMAT_STRING_CHECKS := true
LOCAL_LDLIBS := -lm -latomic
include $(BUILD_EXECUTABLE)