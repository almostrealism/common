/*
 * Copyright 2024 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.llvm;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.File;
import java.io.IOException;

/**
 * Demonstration application for LLVM integration via GraalVM polyglot.
 *
 * <p>This class provides a simple example of how to load and execute LLVM bitcode
 * or IR files using GraalVM's polyglot capabilities. It demonstrates the basic
 * workflow for integrating native C code compiled to LLVM with Java applications.</p>
 *
 * <h2>Prerequisites</h2>
 * <ul>
 *   <li>GraalVM with LLVM toolchain installed ({@code gu install llvm-toolchain})</li>
 *   <li>An LLVM bitcode file named "polyglot" in the current directory</li>
 * </ul>
 *
 * <h2>Creating the LLVM File</h2>
 * <p>To create a compatible LLVM file, compile C code using Clang:</p>
 * <pre>{@code
 * // example.c
 * int main() {
 *     // Your code here
 *     return 0;
 * }
 *
 * // Compile with:
 * // clang -c -emit-llvm example.c -o polyglot
 * }</pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Run from command line:
 * // java -cp ar-llvm.jar org.almostrealism.llvm.App
 * }</pre>
 *
 * <h2>How It Works</h2>
 * <ol>
 *   <li>Creates a GraalVM polyglot context with full access permissions</li>
 *   <li>Loads the LLVM source from a file named "polyglot"</li>
 *   <li>Evaluates the source to get a callable LLVM value</li>
 *   <li>Executes the LLVM code</li>
 * </ol>
 *
 * @see org.graalvm.polyglot.Context
 * @see org.graalvm.polyglot.Source
 * @see org.graalvm.polyglot.Value
 */
class App {
    /**
     * Entry point for the LLVM demonstration application.
     *
     * <p>Loads and executes an LLVM bitcode file named "polyglot" from the current
     * directory. The polyglot context is created with full access permissions to
     * allow native code execution, file I/O, and Java interop.</p>
     *
     * @param args command-line arguments (not used)
     * @throws IOException if the LLVM file cannot be read or parsed
     */
    public static void main(String[] args) throws IOException {
        Context polyglot = Context.newBuilder().
                allowAllAccess(true).build();
        File file = new File("polyglot");
        Source source = Source.newBuilder("llvm", file).build();
        Value cpart = polyglot.eval(source);
        cpart.execute();
    }
}
