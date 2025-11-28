/*
 * Copyright 2016 Michael Murray
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
package io.almostrealism.uml;

import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Target;

/**
 * A marker annotation for types that represent evaluable functions with varying parameters.
 *
 * <p>This annotation identifies types that embody functional or evaluable computations -
 * objects that can be invoked with different input parameters to produce corresponding
 * outputs. It marks types that follow functional programming principles where the same
 * inputs consistently produce the same outputs (though not necessarily pure functions).</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code @Function} is used to mark:</p>
 * <ul>
 *   <li><strong>Evaluable Computations:</strong> Types that compute results from parameters</li>
 *   <li><strong>Computational Graph Nodes:</strong> Operations in hardware-accelerated graphs</li>
 *   <li><strong>Parameterized Operations:</strong> Computations that vary based on input</li>
 *   <li><strong>Reusable Logic:</strong> Functions that can be called multiple times with different arguments</li>
 * </ul>
 *
 * <h2>Functional Characteristics</h2>
 * <p>Types marked with {@code @Function} typically have these characteristics:</p>
 * <ul>
 *   <li><strong>Parameterized:</strong> Accept input parameters that influence the result</li>
 *   <li><strong>Evaluable:</strong> Can be invoked/evaluated to produce output</li>
 *   <li><strong>Reusable:</strong> Can be called multiple times, potentially with different inputs</li>
 *   <li><strong>Deterministic:</strong> Same inputs produce consistent outputs (ideally)</li>
 * </ul>
 *
 * <h2>Common Use Cases</h2>
 *
 * <p><strong>Computational graph operations:</strong></p>
 * <pre>{@code
 * @Function
 * public class AddOperation implements Producer<PackedCollection> {
 *     public PackedCollection evaluate(Object... args) {
 *         PackedCollection a = (PackedCollection) args[0];
 *         PackedCollection b = (PackedCollection) args[1];
 *         return a.add(b);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Parameterized transformations:</strong></p>
 * <pre>{@code
 * @Function
 * public class ScalarMultiply implements Evaluable<PackedCollection> {
 *     private final double scalar;
 *
 *     public ScalarMultiply(double scalar) {
 *         this.scalar = scalar;
 *     }
 *
 *     public PackedCollection evaluate(PackedCollection input) {
 *         return input.multiply(scalar);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Hardware-accelerated kernels:</strong></p>
 * <pre>{@code
 * @Function
 * public class MatrixMultiplyKernel implements Runnable {
 *     private final MemoryBank<?> a, b, result;
 *
 *     public void run() {
 *         // Kernel execution with current parameter values
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Mathematical functions:</strong></p>
 * <pre>{@code
 * @Function
 * public class Polynomial implements Evaluable<Double> {
 *     private final double[] coefficients;
 *
 *     public Double evaluate(Double x) {
 *         double result = 0;
 *         for (int i = 0; i < coefficients.length; i++) {
 *             result += coefficients[i] * Math.pow(x, i);
 *         }
 *         return result;
 *     }
 * }
 * }</pre>
 *
 * <h2>Function vs Procedural Types</h2>
 * <table>
 * <caption>Table</caption>
 *   <tr>
 *     <th>Aspect</th>
 *     <th>@Function Types</th>
 *     <th>Procedural Types</th>
 *   </tr>
 *   <tr>
 *     <td>Primary Purpose</td>
 *     <td>Compute results from inputs</td>
 *     <td>Execute side-effectful actions</td>
 *   </tr>
 *   <tr>
 *     <td>Parameter Dependency</td>
 *     <td>Output depends on parameters</td>
 *     <td>May ignore parameters entirely</td>
 *   </tr>
 *   <tr>
 *     <td>Reusability</td>
 *     <td>Called multiple times with different inputs</td>
 *     <td>Often one-time execution</td>
 *   </tr>
 *   <tr>
 *     <td>Examples</td>
 *     <td>Evaluables, Producers, mathematical operations</td>
 *     <td>Initializers, setup routines, I/O operations</td>
 *   </tr>
 * </table>
 *
 * <h2>Framework Integration</h2>
 * <p>In the Almost Realism framework, {@code @Function} types are:</p>
 * <ul>
 *   <li><strong>Graph Nodes:</strong> Building blocks of computational graphs</li>
 *   <li><strong>Composable:</strong> Can be combined to create complex computations</li>
 *   <li><strong>Hardware-Accelerable:</strong> Eligible for GPU/native compilation</li>
 *   <li><strong>Optimizable:</strong> Subject to graph optimization and fusion</li>
 * </ul>
 *
 * <h2>Annotation Properties</h2>
 * <p>This is a marker annotation with no attributes. Its presence alone conveys
 * semantic information about the type's role as a function. It applies to types
 * (classes, interfaces, enums) via {@code @Target(TYPE)}.</p>
 *
 * <h2>Best Practices</h2>
 * <ul>
 *   <li>Use {@code @Function} for types whose primary purpose is computing results</li>
 *   <li>Prefer deterministic behavior where possible (same input -> same output)</li>
 *   <li>Document parameter requirements and output guarantees</li>
 *   <li>Consider combining with {@link Stateless} for pure functions</li>
 *   <li>Ensure functions are reusable across multiple invocations</li>
 * </ul>
 *
 * @see Stateless
 * @author Michael Murray
 */
@Target(TYPE)
public @interface Function {

}
