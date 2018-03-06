/*
 * Copyright 2018 Michael Murray
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

/**
 * Arbitrary-length integer vector class. Currently very simple and
 * only supports a few needed operations.
 */
public class Veci {
	private int[] data;

	public Veci(int n) {
		data = new int[n];
	}

	public Veci(Veci arg) {
		data = new int[arg.data.length];
		System.arraycopy(arg.data, 0, data, 0, data.length);
	}

	public int length() {
		return data.length;
	}

	public int get(int i) {
		return data[i];
	}

	public void set(int i, int val) {
		data[i] = val;
	}

	public Pair toPair() throws DimensionMismatchException {
		if (length() != 2)
			throw new DimensionMismatchException();
		Pair out = new Pair();
		out.setX(get(0));
		out.setY(get(1));
		return out;
	}

	public Vector toVector() throws DimensionMismatchException {
		if (length() != 3)
			throw new DimensionMismatchException();

		Vector out = new Vector();
		for (int i = 0; i < 3; i++) {
			out.set(i, get(i));
		}

		return out;
	}

	@Deprecated
	public Vecf toVecf() {
		Vecf out = new Vecf(length());
		for (int i = 0; i < length(); i++) {
			out.set(i, get(i));
		}
		return out;
	}
}
