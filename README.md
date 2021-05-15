## Almost Realism Scientific Computing and Generative Art Java/OpenCL Libraries

### What does this do?
It provides data structures for operations in algebra, geometry, and more that are useful
in both scientific computations and the automated production of artwork. These libraries
are based on JOCL (OpenCL bindings for Java), so they are reasonably fast and computations
may take place on a CPU or GPU.

### What does it depend on?
The dependency footprint is unbelievably small. The only dependency that is brought with
this library is JOCL. To use JOCL you will need the native bindings for JNI. They are
available from jocl.org.

### To use the libraries

Add Maven Repository:

        <repositories>
                <repository>
                        <id>internal</id>
                        <name>Archiva Managed Internal Repository</name>
                        <url>http://mvn.almostrealism.org:8080/repository/internal/</url>
                        <releases><enabled>true</enabled></releases>
                        <snapshots><enabled>true</enabled></snapshots>
                </repository>
        </repositories>

Add utils:

        <dependency>
            <groupId>org.almostrealism</groupId>
            <artifactId>ar-utils</artifactId>
            <version>0.21</version>
        </dependency>

### What are the terms of the LICENSE?

Copyright 2021  Michael Murray

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
