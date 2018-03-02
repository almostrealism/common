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

/** Growable array of floats. */
public class FloatList {
	private static final int DEFAULT_SIZE = 10;

	private double[] data = new double[DEFAULT_SIZE];
	private int numElements;

	public void add(double f) {
		if (numElements == data.length) {
			resize(1 + numElements);
		}

		data[numElements++] = f;
		assert numElements <= data.length;
	}

	public int size() {
		return numElements;
	}

	public float get(int index) {
		if (index >= numElements) {
			throw new ArrayIndexOutOfBoundsException(index);
		}

		return (float) data[index];
	}

	public void put(int index, float val) {
		if (index >= numElements) {
			throw new ArrayIndexOutOfBoundsException(index);
		}
		data[index] = val;
	}

	public void trim() {
		if (data.length > numElements) {
			double[] newData = new double[numElements];
			System.arraycopy(data, 0, newData, 0, numElements);
			data = newData;
		}
	}

	public double[] getData() {
		return data;
	}

	private void resize(int minCapacity) {
		int newCapacity = 2 * data.length;
		if (newCapacity == 0) {
			newCapacity = DEFAULT_SIZE;
		}

		if (newCapacity < minCapacity) {
			newCapacity = minCapacity;
		}

		double[] newData = new double[newCapacity];
		System.arraycopy(data, 0, newData, 0, data.length);
		data = newData;
	}
}
