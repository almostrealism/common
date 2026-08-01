#!/bin/sh
set -e

# Builds libMTL.dylib (Metal compute JNI bridge) from the C++/Objective-C++ sources
# in this directory. The shared-memory / direct-buffer helpers formerly in libNIO are
# now compiled at runtime by NativeCompiler (see org.almostrealism.nio.NIO), so there
# is no prebuilt libNIO artifact to build here.
#
# JAVA_HOME is auto-discovered (override by exporting it before running). metal-cpp
# is provided by the amalgamated single header committed next to this file
# (Metal.hpp), so no external metal-cpp installation is required.

cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}"

g++ -c -fPIC \
-std="gnu++20" \
-I"${JAVA_HOME}/include" \
-I"${JAVA_HOME}/include/darwin" \
-I. \
MTL.cpp -o MTL.o

g++ -dynamiclib MTL.o \
-o ../resources/libMTL.dylib \
-framework Metal \
-framework MetalKit \
-framework Cocoa \
-framework QuartzCore \
-framework IOKit \
-framework CoreVideo

rm -f MTL.o
