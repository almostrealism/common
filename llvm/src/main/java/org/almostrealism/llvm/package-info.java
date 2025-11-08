/**
 * LLVM integration via GraalVM polyglot for native code execution.
 * <p>
 * The LLVM module provides integration with LLVM (Low-Level Virtual Machine) and
 * native C code execution through GraalVM's polyglot capabilities. It enables
 * Almost Realism to execute compiled LLVM IR and C code for performance-critical
 * operations.
 * </p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><b>LLVM Integration</b> - Execute LLVM intermediate representation from Java</li>
 *   <li><b>C Interoperability</b> - Call native C functions seamlessly</li>
 *   <li><b>Polyglot Context</b> - Multi-language integration via GraalVM</li>
 *   <li><b>Performance Optimization</b> - Native code execution for critical paths</li>
 * </ul>
 *
 * <h2>Requirements</h2>
 * <ul>
 *   <li>GraalVM with LLVM toolchain installed</li>
 *   <li>LLVM bitcode (.bc) or IR (.ll) files compiled from C/C++</li>
 *   <li>Clang compiler for generating LLVM code</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <h3>Calling C Functions from Java</h3>
 * <pre>{@code
 * // 1. Compile C to LLVM bitcode:
 * //    clang -O3 -c -emit-llvm mycode.c -o mycode.bc
 *
 * // 2. Load and execute in Java:
 * Context ctx = Context.newBuilder()
 *     .allowAllAccess(true)
 *     .build();
 *
 * Source source = Source.newBuilder("llvm", new File("mycode.bc")).build();
 * Value lib = ctx.eval(source);
 *
 * // 3. Call C function:
 * Value multiply = lib.getMember("multiply");
 * int result = multiply.execute(6, 7).asInt();
 * }</pre>
 *
 * <h3>C Code with GraalVM Interop</h3>
 * <pre>{@code
 * // mycode.c
 * #include <graalvm/llvm/polyglot.h>
 *
 * __attribute__((export_name("multiply")))
 * int multiply(int a, int b) {
 *     return a * b;
 * }
 *
 * // Access Java arrays from C
 * void processJavaArray() {
 *     void *arrayType = polyglot_java_type("int[]");
 *     void *array = polyglot_new_instance(arrayType, 4);
 *     polyglot_set_array_element(array, 0, 42);
 * }
 * }</pre>
 *
 * <h2>Compilation Workflow</h2>
 * <pre>{@code
 * # Compile C to LLVM bitcode
 * clang -c -emit-llvm mycode.c -o mycode.bc
 *
 * # Compile with optimizations
 * clang -O3 -c -emit-llvm mycode.c -o mycode.bc
 *
 * # Compile to LLVM IR text format
 * clang -S -emit-llvm mycode.c -o mycode.ll
 * }</pre>
 *
 * <h2>Integration with Other Modules</h2>
 * <ul>
 *   <li><b>ar-hardware</b> - Native code execution for performance-critical kernels</li>
 *   <li><b>ar-code</b> - Alternative compilation target for operations</li>
 * </ul>
 *
 * <h2>Performance Considerations</h2>
 * <p>
 * Use LLVM for:
 * </p>
 * <ul>
 *   <li>Tight numerical loops (matrix operations, signal processing)</li>
 *   <li>Performance-critical kernels</li>
 *   <li>Legacy C/C++ code integration</li>
 *   <li>Platform-specific optimizations</li>
 * </ul>
 *
 * <p>
 * Avoid LLVM for:
 * </p>
 * <ul>
 *   <li>Simple operations (overhead exceeds benefit)</li>
 *   <li>Frequently changing code (compilation overhead)</li>
 *   <li>Operations already optimized in ar-hardware</li>
 * </ul>
 *
 * @see org.graalvm.polyglot.Context
 * @see org.graalvm.polyglot.Source
 * @see org.graalvm.polyglot.Value
 */
package org.almostrealism.llvm;
