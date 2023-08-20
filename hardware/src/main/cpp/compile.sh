#!/bin/sh
set -e

JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.1.jdk/Contents/Home
METAL_HOME=/Users/michael/AlmostRealism/metal-cpp

g++ -c -fPIC \
-std="gnu++20" \
-I${JAVA_HOME}/include \
-I${JAVA_HOME}/include/darwin \
-I${METAL_HOME} \
MTL.cpp -o MTL.o

g++ -dynamiclib MTL.o -o libMTL.dylib -framework Metal -framework MetalKit -framework Cocoa -framework QuartzCore -framework IOKit -framework CoreVideo

#gcc \
#-I${JAVA_HOME}/include \
#-I${JAVA_HOME}/include/darwin \
#-dynamiclib MTL.c -o MTL.o