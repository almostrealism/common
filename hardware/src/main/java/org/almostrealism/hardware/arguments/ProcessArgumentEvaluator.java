/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.hardware.arguments;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.uml.Multiple;

/**
 * Interface for resolving {@link Evaluable} instances from {@link ArrayVariable} arguments.
 *
 * <p>{@link ProcessArgumentEvaluator} provides a strategy for converting {@link ArrayVariable}
 * references (used in {@link io.almostrealism.scope.Scope} compilation) into concrete
 * {@link Evaluable} instances that can produce actual data.</p>
 *
 * <h2>Purpose</h2>
 *
 * <p>When compiling {@link io.almostrealism.compute.Process} trees, arguments are represented
 * as {@link ArrayVariable} instances in the generated code. At runtime, these variables must
 * be resolved to actual {@link Evaluable} objects that produce the data:</p>
 *
 * <pre>{@code
 * // Compilation time: ArrayVariable<Matrix> arg0
 * // Runtime: Evaluable<Multiple<Matrix>> that produces actual matrix data
 *
 * ProcessArgumentEvaluator evaluator = ...;
 * ArrayVariable<Matrix> arg = scope.getArguments().get(0);
 * Evaluable<? extends Multiple<Matrix>> data = evaluator.getEvaluable(arg);
 * }</pre>
 *
 * <h2>Implementations</h2>
 *
 * <ul>
 *   <li><strong>{@link ProcessArgumentMap}:</strong> Maps arguments by tree position with substitution support</li>
 * </ul>
 *
 * <h2>Usage in Process Trees</h2>
 *
 * <p>Used to wire together {@link io.almostrealism.compute.Process} outputs to downstream inputs:</p>
 *
 * <pre>{@code
 * // Build process tree
 * Process<?, Matrix> matmul = matmul(a, b);
 * Process<?, Matrix> add = add(matmul, c);
 *
 * // Compile to operations
 * AcceleratedOperation<Matrix> addOp = compile(add);
 *
 * // Argument 0 of add operation should evaluate matmul output
 * ProcessArgumentEvaluator evaluator = addOp.getArgumentEvaluator();
 * ArrayVariable<Matrix> arg0 = addOp.getScope().getArguments().get(0);
 *
 * // Resolve to matmul evaluable
 * Evaluable<? extends Multiple<Matrix>> matmulData = evaluator.getEvaluable(arg0);
 * }</pre>
 *
 * <h2>Substitution Pattern</h2>
 *
 * <p>Enables dynamic argument substitution for operation reuse:</p>
 *
 * <pre>{@code
 * // Original: add(matmul(a, b), c)
 * ProcessArgumentEvaluator evaluator = ...;
 *
 * // Substitute different inputs
 * evaluator.getEvaluable(arg0);  // Returns matmul(a, b)
 *
 * // With substitution:
 * evaluator.getEvaluable(arg0);  // Returns matmul(x, y) instead
 * }</pre>
 *
 * <h2>Multiple Values</h2>
 *
 * <p>Returns {@link Multiple} to support batch operations:</p>
 *
 * <pre>{@code
 * // Single value
 * Evaluable<? extends Multiple<PackedCollection<?>>> scalar = evaluator.getEvaluable(scalarArg);
 *
 * // Multiple values (batch)
 * Evaluable<? extends Multiple<Matrix>> batch = evaluator.getEvaluable(batchArg);
 * }</pre>
 *
 * @see ProcessArgumentMap
 * @see ArrayVariable
 */
public interface ProcessArgumentEvaluator {
	/**
	 * Resolves an {@link ArrayVariable} to its corresponding {@link Evaluable}.
	 *
	 * @param argument The array variable from compiled scope
	 * @param <T> The element type
	 * @return The evaluable that produces data for this argument
	 */
	<T> Evaluable<? extends Multiple<T>> getEvaluable(ArrayVariable<T> argument);
}
