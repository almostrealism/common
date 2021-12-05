#!/bin/sh

gcc -I/Library/Java/JavaVirtualMachines/jdk-17.0.1.jdk/Contents/Home/include -I/Library/Java/JavaVirtualMachines/jdk-17.0.1.jdk/Contents/Home/include/darwin -dynamiclib $1 -o $2