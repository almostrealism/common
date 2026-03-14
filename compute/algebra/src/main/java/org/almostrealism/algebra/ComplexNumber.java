/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;

import java.util.function.BiFunction;

/**
 * A hardware-accelerated complex number with real and imaginary components.
 *
 * <p>
 * {@link ComplexNumber} extends {@link Pair} to represent complex numbers in the form a + bi,
 * where the first value (x/left/a) represents the real part and the second value (y/right/b)
 * represents the imaginary part. This allows complex arithmetic to leverage hardware
 * acceleration through the computation graph framework.
 * </p>
 *
 * <h2>Memory Layout</h2>
 * <p>
 * A ComplexNumber occupies 2 memory positions:
 * </p>
 * <ul>
 *   <li><strong>Position 0</strong>: Real part (r)</li>
 *   <li><strong>Position 1</strong>: Imaginary part (i)</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Creating Complex Numbers</h3>
 * <pre>{@code
 * // Standard form: a + bi
 * ComplexNumber c1 = new ComplexNumber(3.0, 4.0);  // 3 + 4i
 * double real = c1.getRealPart();                  // 3.0
 * double imag = c1.getImaginaryPart();             // 4.0
 *
 * // Short form accessors
 * double r = c1.r();  // 3.0
 * double i = c1.i();  // 4.0
 *
 * // From existing memory
 * ComplexNumber c2 = new ComplexNumber(memoryData, offset);
 * }</pre>
 *
 * <h3>Inherits Pair Functionality</h3>
 * <pre>{@code
 * ComplexNumber c = new ComplexNumber(2.0, 5.0);
 *
 * // Pair accessor methods also work
 * double x = c.getX();      // 2.0 (same as getRealPart())
 * double y = c.getY();      // 5.0 (same as getImaginaryPart())
 * double a = c.a();         // 2.0
 * double b = c.b();         // 5.0
 *
 * // Pair setter methods
 * c.setX(10.0);  // Sets real part
 * c.setY(20.0);  // Sets imaginary part
 * }</pre>
 *
 * @author  Michael Murray
 * @see Pair
 * @see PackedCollection
 */
public class ComplexNumber extends Pair {
	/**
	 * Constructs a new {@link ComplexNumber} with both real and imaginary parts set to zero.
	 */
	public ComplexNumber() {
	}

	/**
	 * Constructs a new {@link ComplexNumber} backed by the specified {@link MemoryData} at the given offset.
	 * This constructor is used internally for creating complex numbers within {@link PackedCollection}s.
	 *
	 * @param delegate        the memory data to use as backing storage
	 * @param delegateOffset  the offset within the delegate where this complex number's data begins
	 */
	public ComplexNumber(MemoryData delegate, int delegateOffset) {
		super(delegate, delegateOffset);
	}

	/**
	 * Constructs a new {@link ComplexNumber} with the specified real and imaginary parts.
	 *
	 * @param x  the real part
	 * @param y  the imaginary part
	 */
	public ComplexNumber(double x, double y) {
		super(x, y);
	}

	/**
	 * Returns the real part of this complex number.
	 *
	 * @return the real component
	 */
	public double getRealPart() { return left(); }

	/**
	 * Returns the imaginary part of this complex number.
	 *
	 * @return the imaginary component
	 */
	public double getImaginaryPart() { return right(); }

	/**
	 * Short form accessor for the real part.
	 *
	 * @return the real component
	 */
	public double r() { return getRealPart(); }

	/**
	 * Short form accessor for the imaginary part.
	 *
	 * @return the imaginary component
	 */
	public double i() { return getImaginaryPart(); }

	/**
	 * Returns a postprocessor function that creates {@link ComplexNumber}s or {@link PackedCollection}s
	 * of complex numbers from {@link MemoryData}.
	 *
	 * <p>
	 * This method intelligently determines whether to create a single complex number or a collection
	 * based on the shape of the input memory:
	 * </p>
	 * <ul>
	 *   <li>If total size is 2 or offset is non-zero: creates a single {@link ComplexNumber}</li>
	 *   <li>Otherwise: creates a {@link PackedCollection} of complex numbers</li>
	 * </ul>
	 *
	 * @return a function that creates complex number(s) from memory data
	 */
	public static BiFunction<MemoryData, Integer, PackedCollection> complexPostprocessor() {
		return (delegate, offset) -> {
			TraversalPolicy shape = CollectionFeatures.getInstance().shape(delegate);

			if (shape.getTotalSize() == 2 || offset != 0) {
				return new ComplexNumber(delegate, offset);
			} else {
				return new PackedCollection(shape, 1,
						spec -> new ComplexNumber(spec.getDelegate(), spec.getOffset()),
						delegate, offset);
			}
		};
	}
}
