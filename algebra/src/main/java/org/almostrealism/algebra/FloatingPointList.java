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
 * A dynamically-resizing array of {@link Scalar} values.
 *
 * <p>
 * {@link FloatingPointList} provides an ArrayList-like structure for storing scalar values
 * with automatic capacity management. The internal array doubles in size when full,
 * providing amortized O(1) append operations.
 * </p>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * FloatingPointList list = new FloatingPointList();
 *
 * // Add scalar values
 * list.add(new Scalar(1.0));
 * list.add(2.0, 3.0, 4.0);  // Varargs convenience method
 *
 * // Access values
 * Scalar value = list.get(0);  // Returns Scalar(1.0)
 * int size = list.size();       // 4
 *
 * // Trim excess capacity
 * list.trim();
 * }</pre>
 *
 * @deprecated This class will be replaced with ScalarBank for better integration
 *             with hardware-accelerated operations.
 * @author  Michael Murray
 * @see Scalar
 */
public class FloatingPointList {
	private static final int DEFAULT_SIZE = 10;

	private double[] data = new double[DEFAULT_SIZE];
	private int numElements;

	/**
	 * Adds one or more double values to this list.
	 *
	 * @param d  the values to add
	 */
	public void add(double... d) {
		for (double v : d) {
			add(new Scalar(v));
		}
	}

	/**
	 * Adds a {@link Scalar} to this list, resizing if necessary.
	 *
	 * @param f  the scalar to add
	 */
	public void add(Scalar f) {
		if (numElements == data.length) {
			resize(1 + numElements);
		}

		data[numElements++] = f.getValue();
		assert numElements <= data.length;
	}

	/**
	 * Returns the number of elements in this list.
	 *
	 * @return the size
	 */
	public int size() {
		return numElements;
	}

	/**
	 * Returns the {@link Scalar} at the specified index.
	 *
	 * @param index  the index
	 * @return the scalar at that position
	 * @throws ArrayIndexOutOfBoundsException if index is out of bounds
	 */
	public Scalar get(int index) {
		if (index >= numElements) {
			throw new ArrayIndexOutOfBoundsException(index);
		}

		return new Scalar(data[index]);
	}

	/**
	 * Sets the value at the specified index.
	 *
	 * @param index  the index
	 * @param val    the new value
	 * @throws ArrayIndexOutOfBoundsException if index is out of bounds
	 */
	public void put(int index, float val) {
		if (index >= numElements) {
			throw new ArrayIndexOutOfBoundsException(index);
		}
		data[index] = val;
	}

	/**
	 * Trims the internal array to match the current size, reducing memory usage.
	 */
	public void trim() {
		if (data.length > numElements) {
			double[] newData = new double[numElements];
			System.arraycopy(data, 0, newData, 0, numElements);
			data = newData;
		}
	}

	/**
	 * Returns the internal data array.
	 *
	 * @return the underlying double array
	 */
	public double[] getData() {
		return data;
	}

	/**
	 * Resizes the internal array to at least the specified capacity.
	 *
	 * @param minCapacity  the minimum required capacity
	 */
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
