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

package org.almostrealism.audio.sources;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * Generates a single sawtooth wave sample.
 * Computes the output as a linear ramp from -1 to +1 over each wave cycle.
 * Includes PolyBLEP anti-aliasing to smooth the discontinuity at wrap-around.
 *
 * @see WaveCellComputation
 * @see SawtoothWaveCell
 */
public class SawtoothWavePush extends WaveCellComputation implements ExpressionFeatures {

	private final boolean ascending;

	public SawtoothWavePush(SineWaveCellData data, Producer<PackedCollection> envelope,
							PackedCollection output, boolean ascending) {
		super(data, envelope, output);
		this.ascending = ascending;
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		purgeVariables();

		Expression<Double> one = e(1.0);
		Expression<Double> zero = e(0.0);
		Expression<Double> two = e(2.0);
		Expression<Double> negOne = e(-1.0);

		Expression<? extends Number> t = wavePosition().add(phase());
		Expression<? extends Number> frac = t.subtract(t.floor());

		Expression<? extends Number> rawSaw;
		if (ascending) {
			rawSaw = frac.multiply(two).subtract(one);
		} else {
			rawSaw = one.subtract(frac.multiply(two));
		}

		Expression<? extends Number> dt = waveLength();
		Expression<? extends Number> blep = polyBlep(frac, dt, one, zero, two, negOne);

		Expression<? extends Number> antiAliased;
		if (ascending) {
			antiAliased = rawSaw.subtract(blep);
		} else {
			antiAliased = rawSaw.add(blep);
		}

		addVariable(output().assign(
				envelope().multiply(amplitude()).multiply(antiAliased.multiply(depth()))
		));
	}

	/**
	 * PolyBLEP anti-aliasing function for sawtooth waves.
	 */
	private Expression<? extends Number> polyBlep(Expression<? extends Number> t, Expression<? extends Number> dt,
												  Expression<Double> one, Expression<Double> zero,
												  Expression<Double> two, Expression<Double> negOne) {
		Expression<? extends Number> belowDt = conditional(t.lessThan(dt),
				t.divide(dt).subtract(one).pow(two).multiply(negOne),
				zero);

		Expression<? extends Number> oneMinusDt = one.subtract(dt);
		Expression<? extends Number> aboveOneMinusDt = conditional(t.greaterThan(oneMinusDt),
				t.subtract(one).divide(dt).add(one).pow(two),
				zero);

		return belowDt.add(aboveOneMinusDt);
	}
}
