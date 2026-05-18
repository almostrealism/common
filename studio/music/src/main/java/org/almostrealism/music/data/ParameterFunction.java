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

package org.almostrealism.music.data;

import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * A parameterized sinusoidal function that maps a {@link ParameterSet} to a scalar value.
 *
 * <p>The function computes {@code sin(2π * (x*X + y*Y + z*Z + c))} where {@code X}, {@code Y},
 * and {@code Z} are the values from the {@link ParameterSet} and {@code x}, {@code y}, {@code z},
 * and {@code c} are the function coefficients.</p>
 *
 * <p>This is the fundamental building block for the genetic-algorithm parameterization in the
 * pattern system. Different coefficient sets produce different musical selections.</p>
 *
 * @see ParameterSet
 * @see MultipleParameterFunction
 */
public class ParameterFunction implements Function<ParameterSet, Double> {
	/** Coefficients of the sinusoidal function (x, y, z, constant). */
	private double x, y, z, c;

	/** Creates a {@code ParameterFunction} with all-zero coefficients. */
	public ParameterFunction() { }

	/**
	 * Creates a {@code ParameterFunction} with the given coefficients.
	 *
	 * @param x coefficient for the X parameter
	 * @param y coefficient for the Y parameter
	 * @param z coefficient for the Z parameter
	 * @param c constant offset
	 */
	public ParameterFunction(double x, double y, double z, double c) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.c = c;
	}

	/**
	 * Evaluates the function for the given parameter set.
	 *
	 * @param params the input parameter set
	 * @return the sinusoidal output in the range [-1, 1]
	 */
	@Override
	public Double apply(ParameterSet params) {
		return Math.sin(2 * Math.PI * (params.getX() * getX() + params.getY() * getY() + params.getZ() * getZ() + getC()));
	}

	/**
	 * Returns a variant of this function that maps to positive values via {@code Math.abs}.
	 *
	 * @return a function that always returns values in [0, 1]
	 */
	public Function<ParameterSet, Double> positive() {
		// TODO  Should this wrap instead of being continuous?
		return (ParameterSet params) -> Math.abs(apply(params));
	}

	/**
	 * Returns a function that raises {@code base} to a power determined by this function's output.
	 *
	 * @param base the base of the exponentiation
	 * @param unit the unit factor applied to quantize the selection
	 * @param offset the offset added to the quantized selection before exponentiation
	 * @return a function mapping a parameter set to a power-of-base value
	 */
	public Function<ParameterSet, Double> power(double base, int unit, int offset) {
		return (ParameterSet params) -> {
			double selection = apply(params);
			if (selection > 0.0) selection = Math.floor(unit * selection);
			if (selection < 0.0) selection = Math.ceil(unit * selection);
			return Math.pow(base, selection + offset);
		};
	}

	public double getX() { return x; }
	public void setX(double x) { this.x = x; }

	public double getY() { return y; }
	public void setY(double y) { this.y = y; }

	public double getZ() { return z; }
	public void setZ(double z) { this.z = z; }

	public double getC() { return c; }
	public void setC(double c) { this.c = c; }

	/**
	 * Creates a {@code ParameterFunction} with random coefficients in the range [-2, 2].
	 *
	 * @return a new randomly initialized instance
	 */
	public static ParameterFunction random() {
		return random(2.0);
	}

	/**
	 * Creates a {@code ParameterFunction} with random coefficients scaled by {@code scale}.
	 *
	 * @param scale the maximum absolute value of each coefficient
	 * @return a new randomly initialized instance
	 */
	public static ParameterFunction random(double scale) {
		DoubleSupplier rand = () -> (Math.random() - 0.5) * 2.0 * scale;
		return new ParameterFunction(rand.getAsDouble(), rand.getAsDouble(), rand.getAsDouble(), rand.getAsDouble());
	}
}
