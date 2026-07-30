/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ConditionalIndexExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that produces a fixed multi-element constant {@link PackedCollection}.
 *
 * <p>This is the computation behind
 * {@link DefaultTraversableExpressionComputation#fixed(PackedCollection)}: the constant
 * values are baked into the generated kernel as literals via a
 * {@link ConditionalIndexExpression}, so two instances with the same shape but different
 * values produce different generated code. The {@link #signature()} therefore includes
 * the values themselves, following the precedent of
 * {@link ArithmeticSequenceComputation#signature()} — without them, the instruction cache
 * would key only on name and shape, and constants with identical shapes would incorrectly
 * share one compiled kernel (or, with no signature at all, every use would recompile).</p>
 *
 * <p>Instances are only appropriate for small constants: construction is guarded by
 * callers (see {@code CollectionCreationFeatures#c(PackedCollection)}) to shapes smaller
 * than {@code ScopeSettings.maxConditionSize}, which also bounds the signature length.</p>
 *
 * @see DefaultTraversableExpressionComputation#fixed(PackedCollection)
 * @see ArithmeticSequenceComputation
 * @see ConditionalIndexExpression
 *
 * @author Michael Murray
 */
public class FixedCollectionComputation extends TraversableExpressionComputation {
	/**
	 * The constant values produced by this computation. The generated kernel embeds
	 * these values as literals, so they are part of the computation's identity.
	 */
	private final PackedCollection value;

	/**
	 * Lazily computed value suffix appended to the {@link #signature()}.
	 */
	private String valueSignature;

	/**
	 * Constructs a computation that always produces the given values.
	 *
	 * @param value The constant values; the output shape is the value's shape
	 */
	public FixedCollectionComputation(PackedCollection value) {
		super("constant", value.getShape());
		this.value = value;
		init();
	}

	/**
	 * Returns a signature that includes the constant values themselves, so constants
	 * with the same shape but different values never share a cached kernel, while
	 * structurally identical constants reuse one compiled program.
	 *
	 * @return A signature string including the values, or null if the parent
	 *         signature is null or the value is not yet assigned (during base
	 *         class construction)
	 */
	@Override
	public String signature() {
		if (value == null) return null;

		String signature = super.signature();
		if (signature == null) return null;

		if (valueSignature == null) {
			StringBuilder suffix = new StringBuilder("{");

			double[] values = value.toArray(0, value.getMemLength());
			for (int i = 0; i < values.length; i++) {
				if (i > 0) suffix.append(',');
				suffix.append(values[i]);
			}

			valueSignature = suffix.append('}').toString();
		}

		return signature + valueSignature;
	}

	/**
	 * Creates the expression that evaluates the constant values at a bounded index.
	 *
	 * @param args Array of {@link TraversableExpression}s (unused; constants have no inputs)
	 * @return A {@link ConditionalIndexExpression} over the constant values
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new ConditionalIndexExpression(getShape(), value);
	}

	/**
	 * Provides a short-circuit evaluation that copies the constant values into a fresh
	 * collection without compiling a kernel.
	 *
	 * @return An {@link Evaluable} that directly produces a copy of the constant values
	 */
	@Override
	public Evaluable<PackedCollection> getShortCircuit() {
		return args -> {
			PackedCollection v = new PackedCollection(getShape());
			v.setFrom(0, value, 0, value.getMemLength());
			return getPostprocessor() == null ? v : getPostprocessor().apply(v, 0);
		};
	}

	/**
	 * Generates a computation with the specified child processes. Constant
	 * computations have no children, so this computation is returned unchanged.
	 *
	 * @param children List of child {@link Process} instances (ignored)
	 * @return This computation
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return this;
	}

	/**
	 * Returns a string description of this computation, which is the description of
	 * the constant values.
	 *
	 * @return A description of the constant values
	 */
	@Override
	public String description() { return value.describe(); }
}
