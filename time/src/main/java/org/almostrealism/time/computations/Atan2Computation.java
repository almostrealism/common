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

package org.almostrealism.time.computations;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.expression.Atan2;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.DeltaFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;

/**
 * A computation that computes the two-argument arctangent (atan2) function element-wise.
 *
 * <p>For each pair of input elements y[i] and x[i], this computes atan2(y[i], x[i]),
 * which is the angle in radians between the positive x-axis and the point (x[i], y[i]).
 * The result is in the range [-PI, PI].</p>
 *
 * <p>This operation is commonly used in signal processing to compute the phase angle
 * of complex numbers, where y is the imaginary part and x is the real part.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Compute phase of complex FFT output
 * Producer<PackedCollection> real = ...; // Real parts
 * Producer<PackedCollection> imag = ...; // Imaginary parts
 *
 * Atan2Computation phase = new Atan2Computation(shape, imag, real);
 * PackedCollection phaseAngles = phase.get().evaluate();
 * // Result: phase angles in radians
 * }</pre>
 *
 * @see io.almostrealism.expression.Atan2
 * @see org.almostrealism.time.TemporalFeatures#phase(Producer)
 *
 * @author Michael Murray
 */
public class Atan2Computation extends DefaultTraversableExpressionComputation {

	/**
	 * Constructs an Atan2Computation.
	 *
	 * @param shape the output shape
	 * @param y     the y-coordinate producer (imaginary part for complex phase)
	 * @param x     the x-coordinate producer (real part for complex phase)
	 */
	public Atan2Computation(TraversalPolicy shape,
							Producer<PackedCollection> y,
							Producer<PackedCollection> x) {
		super("atan2",
				shape,
				DeltaFeatures.MultiTermDeltaStrategy.NONE,
				true,
				args -> new UniformCollectionExpression(
						"atan2",
						shape,
						in -> Atan2.of(
								(Expression<Double>) in[0],
								(Expression<Double>) in[1]
						),
						args[1], args[2]
				),
				y, x);
	}
}
