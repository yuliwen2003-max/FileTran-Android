#!/usr/bin/env bash
set -euo pipefail
ABI="$1"
HOST_TRIPLET="$2"
CLANG_PREFIX="$3"
API="${4:-24}"
ROOT="/c/Users/Administrator/AndroidStudioProjects/FileTran/.tmp/iperf-src"
NDK="/c/Users/Administrator/AppData/Local/Android/Sdk/ndk/27.0.12077973"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
CC="$TOOLCHAIN/${CLANG_PREFIX}${API}-clang"
CXX="$TOOLCHAIN/${CLANG_PREFIX}${API}-clang++"
AR="$TOOLCHAIN/llvm-ar"
RANLIB="$TOOLCHAIN/llvm-ranlib"
STRIP="$TOOLCHAIN/llvm-strip"

cd "$ROOT/iperf-2.2.1"
rm -f config.status config.log config.h
CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
  ./configure --host="$HOST_TRIPLET" --disable-dependency-tracking --disable-af-packet --disable-tuntap-tun --disable-tuntap-tap > "/tmp/iperf2_${ABI}.log" 2>&1

cd "$ROOT/iperf-3.20"
rm -f config.status config.log src/iperf_config.h
CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
  ./configure --host="$HOST_TRIPLET" --disable-shared --enable-static --disable-dependency-tracking > "/tmp/iperf3_${ABI}.log" 2>&1

echo "Configured $ABI"