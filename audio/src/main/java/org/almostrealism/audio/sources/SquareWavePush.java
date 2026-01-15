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
 * Generates a single square wave sample with configurable duty cycle.
 * Computes the output by comparing the fractional wave position against
 * the duty cycle threshold. Includes PolyBLEP anti-aliasing to reduce
 * aliasing artifacts at discontinuities.
 *
 * @see WaveCellComputation
 * @see SquareWaveCell
 */
public class SquareWavePush extends WaveCellComputation implements ExpressionFeatures {

	public SquareWavePush(SquareWaveCellData data, Producer<PackedCollection> envelope,
						  PackedCollection output) {
		super(data, envelope, data.getDutyCycle(), output);
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

		Expression<? extends Number> dutyCycle = extra();

		Expression<? extends Number> rawSquare = conditional(frac.lessThan(dutyCycle), one, negOne);

		Expression<? extends Number> dt = waveLength();
		Expression<? extends Number> blepRising = polyBlep(frac, dt, one, zero, two, negOne);
		Expression<? extends Number> fracMinusDuty = frac.subtract(dutyCycle);
		Expression<? extends Number> blepFallingArg = fracMinusDuty.add(conditional(frac.lessThan(dutyCycle), one, zero));
		Expression<? extends Number> blepFalling = polyBlep(blepFallingArg, dt, one, zero, two, negOne);

		Expression<? extends Number> antiAliased = rawSquare.add(blepRising).subtract(blepFalling);

		addVariable(output().assign(
				envelope().multiply(amplitude()).multiply(antiAliased.multiply(depth()))
		));
	}

	/**
	 * PolyBLEP (Polynomial Band-Limited Step) anti-aliasing function.
	 * Smooths discontinuities in geometric waveforms to reduce aliasing.
	 *
	 * @param t Phase position (0-1)
	 * @param dt Phase increment per sample (frequency/sampleRate)
	 * @return Correction value to add to the raw waveform
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
