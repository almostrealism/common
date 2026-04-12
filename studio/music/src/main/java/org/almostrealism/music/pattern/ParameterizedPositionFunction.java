/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.music.pattern;

import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;

/**
 * A parameterized sinusoidal function that determines how active a pattern element is
 * at a given measure position.
 *
 * <p>The function combines a regularity-based positional mapping with a sinusoidal
 * rate function to produce an activity value for a given position and scale. This is
 * used by {@link PatternLayerManager} to determine whether a pattern section is active.</p>
 *
 * @see PatternLayerManager
 */
public class ParameterizedPositionFunction {

	/** The ParameterFunction controlling positional regularity (quantization strength). */
	private ParameterFunction regularity;
	/** The ParameterFunction controlling the offset applied before regularity quantization. */
	private ParameterFunction regularityOffset;
	/** The ParameterFunction controlling the sinusoidal rate (frequency multiplier). */
	private ParameterFunction rate;
	/** The ParameterFunction controlling the phase offset for the sinusoidal function. */
	private ParameterFunction rateOffset;

	/** Creates a ParameterizedPositionFunction with all functions null. */
	public ParameterizedPositionFunction() { }

	/**
	 * Creates a ParameterizedPositionFunction with the given parameter functions.
	 *
	 * @param regularity       the regularity function
	 * @param regularityOffset the regularity offset function
	 * @param rate             the rate function
	 * @param rateOffset       the rate offset function
	 */
	public ParameterizedPositionFunction(ParameterFunction regularity, ParameterFunction regularityOffset, ParameterFunction rate, ParameterFunction rateOffset) {
		this.regularity = regularity;
		this.regularityOffset = regularityOffset;
		this.rate = rate;
		this.rateOffset = rateOffset;
	}

	/** Returns the regularity function. */
	public ParameterFunction getRegularity() {
		return regularity;
	}

	/** Sets the regularity function. */
	public void setRegularity(ParameterFunction regularity) {
		this.regularity = regularity;
	}

	/** Returns the regularity offset function. */
	public ParameterFunction getRegularityOffset() {
		return regularityOffset;
	}

	/** Sets the regularity offset function. */
	public void setRegularityOffset(ParameterFunction regularityOffset) {
		this.regularityOffset = regularityOffset;
	}

	/** Returns the rate function. */
	public ParameterFunction getRate() {
		return rate;
	}

	/** Sets the rate function. */
	public void setRate(ParameterFunction rate) {
		this.rate = rate;
	}

	/** Returns the rate offset function. */
	public ParameterFunction getRateOffset() {
		return rateOffset;
	}

	/** Sets the rate offset function. */
	public void setRateOffset(ParameterFunction rateOffset) {
		this.rateOffset = rateOffset;
	}

	/**
	 * Applies this function at the given position, regularized by scale.
	 *
	 * @param params   the parameter set
	 * @param position the measure position
	 * @param scale    the granularity scale
	 * @return the sinusoidal activity value
	 */
	public double apply(ParameterSet params, double position, double scale) {
		return apply(params, regularize(params, position, scale));
	}

	/**
	 * Applies the rate-based sinusoidal function at the given (already-regularized) position.
	 *
	 * @param params   the parameter set
	 * @param position the regularized position
	 * @return the sinusoidal activity value
	 */
	public double apply(ParameterSet params, double position) {
		double r = 2 + rate.apply(params);
		double o = rateOffset.apply(params);
		return Math.sin(Math.PI * (Math.pow(2.0, 10) * position * r + Math.pow(2.0, 3) * o));
	}

	/**
	 * Returns the absolute value of {@link #apply(ParameterSet, double, double)}.
	 *
	 * @param params   the parameter set
	 * @param position the measure position
	 * @param scale    the granularity scale
	 * @return the non-negative activity value
	 */
	public double applyPositive(ParameterSet params, double position, double scale) {
		// TODO  Should this wrap instead of being continuous?
		return Math.abs(apply(params, position, scale));
	}

	/**
	 * Applies regularity quantization to the position, offsetting by the regularity offset.
	 *
	 * @param params   the parameter set
	 * @param position the raw measure position
	 * @param scale    the granularity scale
	 * @return the regularized position
	 */
	protected double regularize(ParameterSet params, double position, double scale) {
		return applyPositional(params, position + regularityOffset.apply(params), scale);
	}

	/** Applies positional quantization based on the regularity parameter. */
	private double applyPositional(ParameterSet params, double position, double scale) {
		double selection = regularity.power(2.0, 3, 4).apply(params);
		position = mod(position, 1.0);

		double regularity = scale * selection;
		position = position / regularity;
		position = mod(position, 1.0);

		return position;
	}

	/** Returns {@code value mod denominator} using floor division. */
	private double mod(double value, double denominator) {
		int result = (int) Math.floor(value / denominator);
		return value - result * denominator;
	}

	/**
	 * Creates a {@code ParameterizedPositionFunction} with all four functions set randomly.
	 *
	 * @return a new random position function
	 */
	public static ParameterizedPositionFunction random() {
		return new ParameterizedPositionFunction(ParameterFunction.random(), ParameterFunction.random(),
												ParameterFunction.random(), ParameterFunction.random());
	}
}
