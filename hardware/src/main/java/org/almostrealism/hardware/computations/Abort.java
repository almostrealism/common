/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware.computations;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationComputationAdapter;
import org.almostrealism.hardware.mem.Bytes;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link OperationComputationAdapter} that generates conditional early return based on a control value.
 *
 * <p>{@link Abort} creates a conditional return statement in compiled code, allowing kernel
 * threads to exit early when a control flag is set. This is useful for:</p>
 * <ul>
 *   <li><strong>Conditional execution:</strong> Skip computation when not needed</li>
 *   <li><strong>Error handling:</strong> Abort on invalid inputs</li>
 *   <li><strong>Optimization:</strong> Early exit when result is known</li>
 * </ul>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * // Create control flag
 * MemoryData control = new Bytes(1);
 * control.setMem(0.0);  // 0 = continue, >0 = abort
 *
 * // Create abort operation
 * Abort abort = new Abort(control);
 *
 * // Compile to scope
 * Scope<Void> scope = abort.getScope(context);
 * }</pre>
 *
 * <h2>Generated Code</h2>
 *
 * <p>For a control value at {@code arg[0]}:</p>
 *
 * <pre>{@code
 * void kernel() {
 *     if (arg[0] > 0) { return; }
 *     // Continue with remaining computation...
 * }
 * }</pre>
 *
 * <h2>Control Value Semantics</h2>
 *
 * <ul>
 *   <li><strong>value &lt;= 0:</strong> Continue execution</li>
 *   <li><strong>value &gt; 0:</strong> Early return (abort)</li>
 * </ul>
 *
 * <pre>{@code
 * // Continue execution
 * control.setMem(0.0);
 * abort.get().run();  // Kernel continues
 *
 * // Abort execution
 * control.setMem(1.0);
 * abort.get().run();  // Kernel returns early
 * }</pre>
 *
 * <h2>Supplier-Based Control</h2>
 *
 * <p>Control value can be provided via {@link java.util.function.Supplier}:</p>
 *
 * <pre>{@code
 * // Dynamic control value
 * Abort abort = new Abort(() -> shouldAbort ? errorFlag : null);
 *
 * // null values use fallback (always continue)
 * }</pre>
 *
 * <h2>Fallback Behavior</h2>
 *
 * <p>When control supplier returns {@code null}, uses static fallback (value = 0.0):</p>
 *
 * <pre>{@code
 * // Fallback: Never aborts
 * private static MemoryData abortFallback;
 * static {
 *     abortFallback = new Bytes(1);
 *     abortFallback.setMem(0.0);
 * }
 * }</pre>
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li><strong>Convergence checking:</strong> Stop iterations when converged</li>
 *   <li><strong>Bounds checking:</strong> Abort on out-of-range inputs</li>
 *   <li><strong>Conditional computation:</strong> Skip expensive operations when not needed</li>
 *   <li><strong>Error propagation:</strong> Abort entire kernel batch on error</li>
 * </ul>
 *
 * <h2>Example: Conditional Gradient Descent</h2>
 *
 * <pre>{@code
 * // Check convergence
 * MemoryData converged = checkConvergence(gradientNorm);
 *
 * // Abort if converged
 * Abort abort = new Abort(converged);
 *
 * // Combined computation
 * Computation<Void> step = sequence(
 *     abort,              // Early return if converged
 *     updateWeights(),    // Only runs if not converged
 *     updateGradient()
 * );
 * }</pre>
 *
 * @see OperationComputationAdapter
 * @see io.almostrealism.scope.HybridScope
 * @see ExpressionFeatures
 */
public class Abort extends OperationComputationAdapter<MemoryData> implements ExpressionFeatures {
	/** Fallback memory data with value 0.0, used when the control supplier returns null (never aborts). */
	private static MemoryData abortFallback;

	static {
		abortFallback = new Bytes(1);
		abortFallback.setMem(0.0);
	}

	/**
	 * Constructs an Abort operation with a fixed control value.
	 *
	 * @param control memory data containing the control value; if value > 0, execution aborts
	 */
	public Abort(MemoryData control) {
		super(() -> new Provider(control));
	}

	/**
	 * Constructs an Abort operation with a dynamic control value supplier.
	 *
	 * <p>If the supplier returns {@code null}, a fallback value of 0.0 is used,
	 * meaning execution will continue.</p>
	 *
	 * @param control supplier providing the control memory data; may return null
	 */
	public Abort(Supplier<MemoryData> control) {
		super(() -> args -> Optional.ofNullable(control.get()).orElse(abortFallback));
	}

	/**
	 * Generates the scope containing the conditional return statement.
	 *
	 * <p>Produces code of the form: {@code if (arg[0] > 0) { return; }}</p>
	 *
	 * @param context the kernel structure context for code generation
	 * @return a scope containing the conditional abort logic
	 */
	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		HybridScope<Void> scope = new HybridScope<>(this);
		scope.code().accept("if (");
		scope.code().accept(getArgument(0).reference(e(0)).getSimpleExpression(getLanguage()));
		scope.code().accept(" > 0) { return; }");
		return scope;
	}
}
