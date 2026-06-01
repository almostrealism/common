#!/bin/sh
set -e

# Builds libMTL.dylib (Metal compute JNI bridge) and libNIO.dylib (shared-memory
# helpers) from the C++/Objective-C++ sources in this directory.
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

g++ -c -fPIC \
-std="gnu++20" \
-I"${JAVA_HOME}/include" \
-I"${JAVA_HOME}/include/darwin" \
NIO.cpp -o NIO.o

g++ -dynamiclib NIO.o \
-o ../resources/libNIO.dylib

rm -f MTL.o NIO.o
