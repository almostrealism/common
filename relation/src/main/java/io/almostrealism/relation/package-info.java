/**
 * Provides the foundational Producer/Evaluable abstraction that enables deferred execution
 * and computation graph optimization throughout the Almost Realism framework.
 *
 * <p>This package contains the core interfaces that separate <b>computation description</b>
 * from <b>computation execution</b>, enabling static analysis, optimization, and GPU compilation.</p>
 *
 * <h2>Core Abstraction</h2>
 *
 * <p>The Producer/Evaluable pattern separates concerns:</p>
 * <ul>
 *   <li>{@link Producer} - Describes a computation (factory for Evaluable)</li>
 *   <li>{@link Evaluable} - Executes a computation (produces result)</li>
 * </ul>
 *
 * <h2>Key Interfaces</h2>
 *
 * <ul>
 *   <li>{@link Producer} - Computation factory (Supplier of Evaluable)</li>
 *   <li>{@link Evaluable} - Direct computation execution</li>
 *   <li>{@link Tree} - Tree traversal and analysis</li>
 *   <li>{@link Countable} - Parallelism count specification</li>
 *   <li>{@link Composition} - Combines two Producers</li>
 *   <li>{@link Factor} - Transforms a Producer</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Build computation graph
 * Producer<T> op = operation.compose(a, b);
 *
 * // Optimize
 * Producer<T> optimized = Process.optimized(op);
 *
 * // Execute
 * T result = optimized.get().evaluate(args);
 * }</pre>
 *
 * @author Michael Murray
 */
package io.almostrealism.relation;