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

package org.almostrealism.time;

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairPool;
import org.almostrealism.algebra.Scalar;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.PooledMem;
import org.almostrealism.time.computations.CursorPairIncrement;

import java.util.function.Supplier;

public class CursorPair extends Pair {
	public CursorPair() { super(0, 0); }

	public CursorPair(double cursor, double delayCursor) { super(cursor, delayCursor); }

	public void setCursor(double v) { setA(v); }
	public double getCursor() { return getA(); }

	public void setDelayCursor(double v) {
		setB(v);
		// TODO  This is due to a CL "bug" (or something), it should be removed
		if (Math.abs(getB() - v) > 1) {
			throw new UnsupportedOperationException();
		}
		if (getDelayCursor() <= getCursor()) setDelayCursor(getCursor() + 1);
	}

	public double getDelayCursor() { return getB(); }

	public Supplier<Runnable> increment(Producer<Scalar> value) {
		return new CursorPairIncrement(() -> new Provider<>(this), value);
	}
}
