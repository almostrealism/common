/*
 * Copyright 2020 Michael Murray
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
import org.almostrealism.algebra.Scalar;
import org.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public class CursorPair extends Pair {
	public CursorPair() { super(0, 0); }

	public void setCursor(double v) { setA(v); }
	public double getCursor() { return getA(); }

	public void setDelayCursor(double v) {
		setB(v);
		if (getDelayCursor() <= getCursor()) setDelayCursor(getCursor() + 1);
	}

	public double getDelayCursor() { return getB(); }

	public Supplier<Runnable> increment(Evaluable<Scalar> value) {
		return () -> () -> {
			double v = value.evaluate().getValue();
			setCursor(getCursor() + v);
			setDelayCursor(getDelayCursor() + v);
		};
	}
}
