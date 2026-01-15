/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.filter;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * Hardware-accelerated computation for biquad IIR filter processing.
 * <p>
 * Implements the Direct Form I biquad filter equation:
 * {@code y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]}
 * <p>
 * This computation reads filter coefficients and delay line state from
 * {@link BiquadFilterData}, applies the filter to the input sample,
 * writes the output, and updates the delay line state.
 * <p>
 * The implementation uses standard {@link CollectionProducer} operations
 * (multiply, add, subtract) for better optimization and composition
 * with the rest of the framework.
 *
 * @see BiquadFilterCell
 * @see BiquadFilterData
 */
public class BiquadFilterComputation implements Supplier<Runnable>, CodeFeatures {

	private final BiquadFilterData data;
	private final Producer<PackedCollection> input;
	private final PackedCollection output;

	public BiquadFilterComputation(BiquadFilterData data, Producer<PackedCollection> input,
								   PackedCollection output) {
		this.data = data;
		this.input = input;
		this.output = output;
	}

	@Override
	public Runnable get() {
		OperationList ops = new OperationList("BiquadFilter");

		// Get producers for all coefficients and state
		Producer<PackedCollection> b0 = data.getB0();
		Producer<PackedCollection> b1 = data.getB1();
		Producer<PackedCollection> b2 = data.getB2();
		Producer<PackedCollection> a1 = data.getA1();
		Producer<PackedCollection> a2 = data.getA2();
		Producer<PackedCollection> x1 = data.getX1();
		Producer<PackedCollection> x2 = data.getX2();
		Producer<PackedCollection> y1 = data.getY1();
		Producer<PackedCollection> y2 = data.getY2();

		// Compute Direct Form I: y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
		CollectionProducer feedforward = add(
				multiply(b0, input),
				add(multiply(b1, x1), multiply(b2, x2))
		);

		CollectionProducer feedback = add(
				multiply(a1, y1),
				multiply(a2, y2)
		);

		CollectionProducer y0 = subtract(feedforward, feedback);

		// Write output
		ops.add(a(p(output), y0));

		// Update delay lines: shift history
		// Order matters: must read old values before overwriting
		ops.add(a(p(data.x2()), x1));
		ops.add(a(p(data.x1()), input));
		ops.add(a(p(data.y2()), y1));
		ops.add(a(p(data.y1()), y0));

		return ops.get();
	}
}
