/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.PooledMem;

import java.util.function.BiFunction;

public class Scalar extends Pair<Scalar> implements Comparable<Scalar> {
	public static final double EPSILON = 1.19209290e-07;
	public static final double TWO_PI = 6.283185307179586232;
	public static final double PI = TWO_PI * 0.5;

	public Scalar() { this(true); }
	public Scalar(boolean certain) { if (certain) setCertainty(1.0); }
	public Scalar(double v) { setValue(v); setCertainty(1.0); }
	public Scalar(double v, double c) { setValue(v); setCertainty(c); }

	public Scalar(MemoryData delegate, int delegateOffset) {
		super(delegate, delegateOffset);
	}

	@Override
	protected void init() {
		super.init();
		// TODO  Need to determine if certainty should be set to 1.0
		//       based on conditions such as whether we reserved a
		//       delegate from the pool
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

	public Scalar clone() {
		return new Scalar(getValue(), getCertainty());
	}

	@Override
	public PooledMem getDefaultDelegate() { return ScalarPool.getLocal(); }

	public static TraversalPolicy shape() {
		return new TraversalPolicy(2);
	}

	public static PackedCollection<Scalar> scalarBank(int count) {
		return new PackedCollection<>(new TraversalPolicy(count, 2), 1, delegateSpec ->
				new Scalar(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	public static PackedCollection<Scalar> scalarBank(int count, MemoryData delegate, int delegateOffset) {
		return new PackedCollection<>(new TraversalPolicy(count, 2), 1, delegateSpec ->
				new Scalar(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}

	public static Producer<Scalar> blank() {
		return new DynamicProducerForMemoryData<>(() -> new Scalar(false), Scalar::scalarBank);
	}

	public static BiFunction<MemoryData, Integer, Pair<?>> postprocessor() {
		return (delegate, offset) -> new Scalar(delegate, offset);
	}

	public static BiFunction<MemoryData, Integer, PackedCollection<Scalar>> scalarBankPostprocessor() {
		return (output, offset) -> Scalar.scalarBank(output.getMemLength() / 2, output, offset);
	}

	/**
	 * Returns 1 if the sign of the given argument is positive; -1 if
	 * negative; 0 if 0.
	 */
	@Deprecated
	public static int sgn(double f) {
		if (f > 0) {
			return 1;
		} else if (f < 0) {
			return -1;
		}

		return 0;
	}

	@Deprecated
	public static float[] toFloat(double d[]) {
		float f[] = new float[d.length];
		for (int i = 0; i < d.length; i++) f[i] = (float) d[i];
		return f;
	}

	@Deprecated
	public static float ssFunc(final double t, final float p[]) {
		return ssFunc(t, p, 0);
	}

	@Deprecated
	public static float ssFunc(final double t, final float p[], int pOff) {
		return (float) (Math.pow(Math.pow(Math.abs(Math.cos(p[0 + pOff] * t / 4)) / p[1 + pOff], p[4 + pOff]) +
				Math.pow(Math.abs(Math.sin(p[0 + pOff] * t / 4)) / p[2 + pOff], p[5 + pOff]), 1 / p[3 + pOff]));
	}
}
