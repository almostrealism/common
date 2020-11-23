/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.algebra;

import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.hardware.PooledMem;
import org.almostrealism.util.DynamicScalarProducer;

public class Scalar extends Pair implements Comparable<Scalar> {
	public static final double EPSILON = 1.19209290e-07;
	public static final double TWO_PI = 6.283185307179586232;
	public static final double PI = TWO_PI * 0.5;

	public Scalar() { this(true); }
	public Scalar(boolean certain) { if (certain) setCertainty(1.0); }
	public Scalar(double v) { setValue(v); setCertainty(1.0); }
	public Scalar(double v, double c) { setValue(v); setCertainty(c); }

	protected Scalar(MemWrapper delegate, int delegateOffset) {
		super(delegate, delegateOffset);
		setCertainty(1.0);
	}

	public Scalar setValue(double v) { setLeft(v); return this; }
	public Scalar setCertainty(double c) { setRight(c); return this; }
	public double getValue() { return left(); }
	public double getCertainty() { return right(); }

	@Override
	public int compareTo(Scalar s) {
		double m = 2 * Math.max(Math.abs(getValue()), Math.abs(s.getValue()));
		return (int) ((this.getValue() - s.getValue() / m) * Integer.MAX_VALUE);
	}

	public Object clone() {
		Scalar s = new Scalar(getValue(), getCertainty());
		return s;
	}

	@Override
	public PooledMem getDefaultDelegate() { return ScalarPool.getLocal(); }

	public static ScalarProducer blank() {
		return new DynamicScalarProducer(args -> new Scalar(false));
	}

	public static Scalar sel(double a, double b, double c) {
		return new Scalar((a >= 0 ? b : c));
	}

	public static boolean fuzzyZero(double x) {
		return Math.abs(x) < EPSILON;
	}

	public static Scalar atan2(double y, double x) {
		double coeff_1 = PI / 4.0;
		double coeff_2 = 3.0 * coeff_1;
		double abs_y = Math.abs(y);
		double angle;

		if (x >= 0.0) {
			double r = (x - abs_y) / (x + abs_y);
			angle = coeff_1 - coeff_1 * r;
		} else {
			double r = (x + abs_y) / (abs_y - x);
			angle = coeff_2 - coeff_1 * r;
		}

		return new Scalar(((y < 0.0f) ? -angle : angle));
	}

	/**
	 * Returns 1 if the sign of the given argument is positive; -1 if
	 * negative; 0 if 0.
	 */
	public static int sgn(double f) {
		if (f > 0) {
			return 1;
		} else if (f < 0) {
			return -1;
		}

		return 0;
	}

	/** Clamps argument between min and max values. */
	public static float clamp(double val, double min, double max) {
		if (val < min) return (float) min;
		if (val > max) return (float) max;
		return (float) val;
	}

	/** Clamps argument between min and max values. */
	public static int clamp(int val, int min, int max) {
		if (val < min) return min;
		if (val > max) return max;
		return val;
	}

	public static float[] toFloat(double d[]) {
		float f[] = new float[d.length];
		for (int i = 0; i < d.length; i++) f[i] = (float) d[i];
		return f;
	}

	public static float ssFunc(final double t, final float p[]) {
		return ssFunc(t, p, 0);
	}

	public static float ssFunc(final double t, final float p[], int pOff) {
		return (float) (Math.pow(Math.pow(Math.abs(Math.cos(p[0 + pOff] * t / 4)) / p[1 + pOff], p[4 + pOff]) +
				Math.pow(Math.abs(Math.sin(p[0 + pOff] * t / 4)) / p[2 + pOff], p[5 + pOff]), 1 / p[3 + pOff]));
	}
}
