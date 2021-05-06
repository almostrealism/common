#!/bin/sh

gcc -framework OpenCL -I/Library/Java/JavaVirtualMachines/jdk-14.0.1.jdk/Contents/Home/include -I/Library/Java/JavaVirtualMachines/jdk-14.0.1.jdk/Contents/Home/include/darwin -dynamiclib $1 -o $2