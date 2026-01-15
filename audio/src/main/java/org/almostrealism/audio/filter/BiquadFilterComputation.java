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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.Ops;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.List;

/**
 * Hardware-accelerated computation for biquad IIR filter processing.
 * <p>
 * Implements the Direct Form I biquad filter equation:
 * {@code y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]}
 * <p>
 * This computation reads filter coefficients and delay line state from
 * {@link BiquadFilterData}, applies the filter to the input sample,
 * writes the output, and updates the delay line state.
 *
 * @see BiquadFilterCell
 * @see BiquadFilterData
 */
public class BiquadFilterComputation extends OperationComputationAdapter<PackedCollection> implements ExpressionFeatures {

	public BiquadFilterComputation(BiquadFilterData data, Producer<PackedCollection> input,
								   PackedCollection output) {
		super(Ops.o().p(output),
				input,
				data.getB0(),
				data.getB1(),
				data.getB2(),
				data.getA1(),
				data.getA2(),
				data.getX1(),
				data.getX2(),
				data.getY1(),
				data.getY2());
	}

	private BiquadFilterComputation(Producer... arguments) {
		super(arguments);
	}

	@Override
	public ParallelProcess<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		return new BiquadFilterComputation(children.toArray(Producer[]::new));
	}

	private ArrayVariable<Double> getOutput() { return getArgument(0); }
	private ArrayVariable<Double> getInput() { return getArgument(1); }
	private ArrayVariable<Double> getB0() { return getArgument(2); }
	private ArrayVariable<Double> getB1() { return getArgument(3); }
	private ArrayVariable<Double> getB2() { return getArgument(4); }
	private ArrayVariable<Double> getA1() { return getArgument(5); }
	private ArrayVariable<Double> getA2() { return getArgument(6); }
	private ArrayVariable<Double> getX1() { return getArgument(7); }
	private ArrayVariable<Double> getX2() { return getArgument(8); }
	private ArrayVariable<Double> getY1() { return getArgument(9); }
	private ArrayVariable<Double> getY2() { return getArgument(10); }

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		purgeVariables();

		Expression<? extends Number> x0 = getInput().valueAt(0);
		Expression<? extends Number> b0 = getB0().valueAt(0);
		Expression<? extends Number> b1 = getB1().valueAt(0);
		Expression<? extends Number> b2 = getB2().valueAt(0);
		Expression<? extends Number> a1 = getA1().valueAt(0);
		Expression<? extends Number> a2 = getA2().valueAt(0);
		Expression<? extends Number> x1 = getX1().valueAt(0);
		Expression<? extends Number> x2 = getX2().valueAt(0);
		Expression<? extends Number> y1 = getY1().valueAt(0);
		Expression<? extends Number> y2 = getY2().valueAt(0);

		// Direct Form I: y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
		Expression<? extends Number> y0 = b0.multiply(x0)
				.add(b1.multiply(x1))
				.add(b2.multiply(x2))
				.subtract(a1.multiply(y1))
				.subtract(a2.multiply(y2));

		// Write output
		addVariable(getOutput().reference(e(0)).assign(y0));

		// Update delay lines: shift history
		addVariable(getX2().reference(e(0)).assign(x1));
		addVariable(getX1().reference(e(0)).assign(x0));
		addVariable(getY2().reference(e(0)).assign(y1));
		addVariable(getY1().reference(e(0)).assign(y0));
	}
}
