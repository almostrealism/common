#!/bin/sh
set -e

# Builds libARCUDA-linux-<arch>.so (CUDA compute JNI bridge) from CUDA.cpp in this
# directory, writing it to ../resources where org.almostrealism.hardware.cuda.CU
# loads it from at runtime.
#
# Requires a CUDA toolkit (headers for cuda.h and nvrtc.h, and libcuda/libnvrtc to
# link against). CUDA_HOME defaults to /usr/local/cuda and JAVA_HOME is discovered
# from the javac on PATH; export either to override.

cd "$(dirname "$0")"

CUDA_HOME="${CUDA_HOME:-/usr/local/cuda}"
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
ARCH="$(uname -m)"

g++ -shared -fPIC -O2 \
-std=c++17 \
-I"${JAVA_HOME}/include" \
-I"${JAVA_HOME}/include/linux" \
-I"${CUDA_HOME}/include" \
CUDA.cpp \
-o "../resources/libARCUDA-linux-${ARCH}.so" \
-L"${CUDA_HOME}/lib64" \
-Wl,-rpath,"${CUDA_HOME}/lib64" \
-lcuda -lnvrtc -ldl
