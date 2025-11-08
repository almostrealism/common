/**
 * <p>
 * Provides hardware-accelerated algebraic computations for scalars, vectors, matrices, and tensors
 * in the Almost Realism framework.
 * </p>
 *
 * <h2>Overview</h2>
 * <p>
 * The algebra module is a foundational component providing efficient implementations of mathematical
 * operations required for machine learning, physics simulation, and graphics rendering. All operations
 * are backed by {@link org.almostrealism.collect.PackedCollection} for hardware acceleration and
 * support deferred execution through the {@link io.almostrealism.relation.Producer}/
 * {@link io.almostrealism.relation.Evaluable} pattern.
 * </p>
 *
 * <h2>Core Data Types</h2>
 *
 * <h3>Fundamental Types</h3>
 * <ul>
 *   <li>{@link org.almostrealism.algebra.Scalar} - Single-element value (0-dimensional tensor)</li>
 *   <li>{@link org.almostrealism.algebra.Vector} - 3-dimensional vector with x, y, z components</li>
 *   <li>{@link org.almostrealism.algebra.Pair} - 2-element tuple for complex numbers and coordinate pairs</li>
 *   <li>{@link org.almostrealism.algebra.Tensor} - Generic multi-dimensional array with arbitrary shape</li>
 *   <li>{@link org.almostrealism.algebra.Matrix3D} - 3×3 matrix for 3D transformations</li>
 * </ul>
 *
 * <h3>Utility Types</h3>
 * <ul>
 *   <li>{@link org.almostrealism.algebra.ZeroVector} - Singleton zero vector [0, 0, 0]</li>
 *   <li>{@link org.almostrealism.algebra.UnityVector} - Singleton unit vectors along each axis</li>
 *   <li>{@link org.almostrealism.algebra.ComplexNumber} - Complex number with real and imaginary components</li>
 *   <li>{@link org.almostrealism.algebra.Gradient} - Gradient computation for automatic differentiation</li>
 * </ul>
 *
 * <h2>Feature Interfaces</h2>
 * <p>
 * The module provides several feature interfaces that serve as mixins for convenient access to
 * factory methods and operations:
 * </p>
 * <ul>
 *   <li>{@link org.almostrealism.algebra.ScalarFeatures} - Factory methods for scalar operations</li>
 *   <li>{@link org.almostrealism.algebra.VectorFeatures} - Factory methods for vector operations (dot, cross, normalize)</li>
 *   <li>{@link org.almostrealism.algebra.PairFeatures} - Factory methods for pairs and complex number operations</li>
 *   <li>{@link org.almostrealism.algebra.MatrixFeatures} - Matrix operations (identity, diagonal, multiplication, attention)</li>
 *   <li>{@link org.almostrealism.algebra.AlgebraFeatures} - Advanced operations (broadcasting, weighted sums, producer matching)</li>
 * </ul>
 *
 * <h3>Usage Pattern</h3>
 * <pre>{@code
 * public class MyClass implements VectorFeatures, MatrixFeatures {
 *     public void example() {
 *         // Use factory methods directly
 *         CollectionProducer<Vector> v1 = vector(1.0, 0.0, 0.0);
 *         CollectionProducer<Vector> v2 = vector(0.0, 1.0, 0.0);
 *
 *         // Vector operations
 *         CollectionProducer<PackedCollection<?>> dot = dotProduct(v1, v2);
 *         CollectionProducer<Vector> cross = crossProduct(v1, v2);
 *
 *         // Matrix operations
 *         CollectionProducer<PackedCollection<?>> identity = identity(shape(3, 3));
 *         CollectionProducer<PackedCollection<?>> result = matmul(identity, v1);
 *     }
 * }
 * }</pre>
 *
 * <h2>Computation Classes</h2>
 *
 * <h3>Matrix Computations</h3>
 * <ul>
 *   <li>{@link org.almostrealism.algebra.computations.IdentityMatrixComputation} - Generates identity matrices</li>
 *   <li>{@link org.almostrealism.algebra.computations.DiagonalMatrixComputation} - Creates diagonal matrices from vectors</li>
 *   <li>{@link org.almostrealism.algebra.computations.ScalarMatrixComputation} - Creates scalar matrices (s·I)</li>
 *   <li>{@link org.almostrealism.algebra.computations.WeightedSumComputation} - Fundamental building block for matmul, convolution, attention</li>
 * </ul>
 *
 * <h3>Control Flow and Selection</h3>
 * <ul>
 *   <li>{@link org.almostrealism.algebra.computations.Choice} - Data selection: selects from pre-computed values</li>
 *   <li>{@link org.almostrealism.algebra.computations.Switch} - Control flow: executes one of multiple computation branches</li>
 *   <li>{@link org.almostrealism.algebra.computations.HighestRank} - Finds highest-ranked (smallest non-zero) value in distance arrays</li>
 *   <li>{@link org.almostrealism.algebra.computations.ProducerWithRankAdapter} - Associates producers with rank values for sorting/selection</li>
 * </ul>
 *
 * <h2>Hardware Acceleration</h2>
 * <p>
 * All algebra operations support hardware acceleration through the Almost Realism compute framework:
 * </p>
 * <ul>
 *   <li><b>Memory Backend:</b> Operations backed by {@link org.almostrealism.collect.PackedCollection}
 *       which uses {@link io.almostrealism.code.MemoryData} for efficient CPU/GPU data management</li>
 *   <li><b>Deferred Execution:</b> Operations build computation graphs via the
 *       {@link io.almostrealism.relation.Producer} pattern, enabling optimization and batching</li>
 *   <li><b>Multi-Device Support:</b> Native JNI, OpenCL, and Metal backends (configured via AR_HARDWARE_DRIVER)</li>
 *   <li><b>Kernel Compilation:</b> Operations are compiled to optimized kernels at runtime</li>
 * </ul>
 *
 * <h2>Automatic Differentiation</h2>
 * <p>
 * Many computation classes support automatic differentiation through the
 * {@link org.almostrealism.collect.CollectionProducer#delta(io.almostrealism.relation.Producer)} method:
 * </p>
 * <pre>{@code
 * // Create a computation graph
 * CollectionProducer<Vector> input = v(Vector.class);
 * CollectionProducer<PackedCollection<?>> weights = v(PackedCollection.class);
 * CollectionProducer<PackedCollection<?>> output = matmul(weights, input);
 *
 * // Compute gradient with respect to weights
 * CollectionProducer<PackedCollection<?>> gradient = output.delta(weights);
 * }</pre>
 *
 * <h2>Integration with Other Modules</h2>
 * <ul>
 *   <li><b>collect:</b> Provides {@link org.almostrealism.collect.PackedCollection} and
 *       {@link io.almostrealism.collect.TraversalPolicy} for multi-dimensional data</li>
 *   <li><b>graph:</b> Uses algebra operations in {@link org.almostrealism.graph.Cell} computations
 *       for neural network layers</li>
 *   <li><b>ml:</b> Transformer models use {@link org.almostrealism.algebra.computations.WeightedSumComputation}
 *       for attention mechanisms</li>
 *   <li><b>physics:</b> Uses {@link org.almostrealism.algebra.Vector} for particle positions and velocities</li>
 * </ul>
 *
 * <h2>Key Concepts</h2>
 *
 * <h3>Broadcasting</h3>
 * <p>
 * NumPy-style broadcasting is supported via {@link org.almostrealism.algebra.AlgebraFeatures#broadcast}
 * and {@link org.almostrealism.algebra.AlgebraFeatures#broadcastSum}:
 * </p>
 * <pre>{@code
 * // Broadcasting (3, 1) with (1, 4) → (3, 4)
 * CollectionProducer<PackedCollection<?>> result = broadcast(
 *     shape(3, 4),
 *     vectorA,  // shape (3, 1)
 *     vectorB   // shape (1, 4)
 * );
 * }</pre>
 *
 * <h3>Traversal Policies</h3>
 * <p>
 * All operations work with {@link io.almostrealism.collect.TraversalPolicy} to define tensor shapes:
 * </p>
 * <pre>{@code
 * TraversalPolicy shape = shape(3, 4, 5);  // 3×4×5 tensor
 * int dims = shape.getDimensions();         // 3
 * int totalSize = shape.getTotalSize();     // 60
 * }</pre>
 *
 * <h3>Producer Matching</h3>
 * <p>
 * {@link org.almostrealism.algebra.AlgebraFeatures} provides utilities for matching producers in
 * computation graphs, used extensively in automatic differentiation:
 * </p>
 * <pre>{@code
 * if (AlgebraFeatures.match(producer1, target) &&
 *     AlgebraFeatures.cannotMatch(producer2, target)) {
 *     // Differentiate with respect to producer1
 * }
 * }</pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Vector Operations</h3>
 * <pre>{@code
 * // Vector creation and operations
 * CollectionProducer<Vector> v1 = vector(1.0, 2.0, 3.0);
 * CollectionProducer<Vector> v2 = vector(4.0, 5.0, 6.0);
 *
 * // Component extraction
 * CollectionProducer<Scalar> xComp = x(v1);
 *
 * // Vector arithmetic
 * CollectionProducer<PackedCollection<?>> dot = dotProduct(v1, v2);
 * CollectionProducer<Vector> cross = crossProduct(v1, v2);
 * CollectionProducer<Vector> normalized = normalize(v1);
 * }</pre>
 *
 * <h3>Matrix Operations</h3>
 * <pre>{@code
 * // Matrix creation
 * CollectionProducer<PackedCollection<?>> identity = identity(shape(4, 4));
 * CollectionProducer<PackedCollection<?>> diagonal = diagonal(vector(1, 2, 3, 4));
 * CollectionProducer<PackedCollection<?>> scalarMat = scalarMatrix(shape(4, 4), c(5.0));
 *
 * // Matrix multiplication
 * CollectionProducer<PackedCollection<?>> matA = ...;
 * CollectionProducer<PackedCollection<?>> vecB = ...;
 * CollectionProducer<PackedCollection<?>> result = matmul(matA, vecB);
 * }</pre>
 *
 * <h3>Complex Numbers</h3>
 * <pre>{@code
 * // Complex number operations
 * CollectionProducer<Pair> c1 = pair(3.0, 4.0);  // 3 + 4i
 * CollectionProducer<Pair> c2 = pair(1.0, 2.0);  // 1 + 2i
 * CollectionProducer<Pair> product = multiplyComplex(c1, c2);  // (3+4i)(1+2i) = -5+10i
 * }</pre>
 *
 * <h3>Weighted Sum for Attention</h3>
 * <pre>{@code
 * // Transformer attention mechanism using weighted sum
 * TraversalPolicy resultShape = shape(seqLen, dim);
 * TraversalPolicy inputPos = shape(seqLen, dim, headSize);
 * TraversalPolicy weightPos = shape(seqLen, dim, headSize);
 * TraversalPolicy groupShape = shape(1, 1, headSize);  // Sum over headSize dimension
 *
 * WeightedSumComputation attention = new WeightedSumComputation(
 *     resultShape, inputPos, weightPos,
 *     groupShape, groupShape,
 *     queryKeyProduct, values
 * );
 * }</pre>
 *
 * @author  Michael Murray
 * @see org.almostrealism.collect.PackedCollection
 * @see io.almostrealism.collect.TraversalPolicy
 * @see io.almostrealism.relation.Producer
 * @see org.almostrealism.graph
 * @see org.almostrealism.ml
 */
package org.almostrealism.algebra;