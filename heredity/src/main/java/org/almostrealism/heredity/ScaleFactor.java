/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.heredity;

import io.almostrealism.relation.Factor;
import io.almostrealism.util.NumberFormats;
import org.almostrealism.algebra.ScalarFeatures;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.Optional;

/**
 * A {@link Factor} implementation that multiplies input values by a scalar.
 *
 * <p>This is the most common factor type used in genetic algorithms with this framework.
 * It represents a simple scalar multiplier that can be evolved to control the magnitude
 * of values in a computation.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create factors with different scales
 * ScaleFactor half = new ScaleFactor(0.5);
 * ScaleFactor double = new ScaleFactor(2.0);
 * ScaleFactor negative = new ScaleFactor(-1.0);
 *
 * // Apply factor to a producer
 * Producer<PackedCollection<?>> input = ...;
 * Producer<PackedCollection<?>> scaled = half.getResultant(input);
 *
 * // Modify scale value
 * half.setScaleValue(0.75);
 * }</pre>
 *
 * @see HeredityFeatures#g(double...)
 * @see Factor
 */
public class ScaleFactor implements Factor<PackedCollection<?>>, ScalarFeatures, CollectionFeatures {
	private PackedCollection<?> scale;

	/**
	 * Constructs a new {@code ScaleFactor} with a scale of 0.0.
	 */
	public ScaleFactor() {
		scale = new PackedCollection<>(1);
		scale.setMem(0, 0.0);
	}

	/**
	 * Constructs a new {@code ScaleFactor} with the specified scale value.
	 *
	 * @param scale the scalar multiplier value
	 */
	public ScaleFactor(double scale) {
		this.scale = new PackedCollection<>(1);
		this.scale.setMem(0, scale);
	}

	/**
	 * Constructs a new {@code ScaleFactor} using the specified {@link PackedCollection}.
	 *
	 * @param scale the PackedCollection containing the multiplier value
	 */
	public ScaleFactor(PackedCollection<?> scale) { this.scale = scale; }

	/**
	 * Returns a producer that multiplies the input by this factor's scale value.
	 *
	 * @param value the input producer to scale
	 * @return a producer that produces the scaled result
	 */
	@Override
	public Producer<PackedCollection<?>> getResultant(Producer<PackedCollection<?>> value) {
		return multiply(value, (Producer) p(scale), args -> {
			PackedCollection<?> result = new PackedCollection<>(1);

//			if (value instanceof StaticCollectionComputation) {
//				result.setMem(((StaticCollectionComputation) value).getValue().toDouble(0) * scale.toDouble(0));
//			} else {
				result.setMem(value.get().evaluate(args).toDouble(0) * scale.toDouble(0));
//			}

			return result;
		});
	}

	/**
	 * Sets the scale value.
	 *
	 * @param s the new scale value
	 */
	public void setScaleValue(double s) {
		this.scale = new PackedCollection<>(1);
		this.scale.setMem(0, s);
	}

	/**
	 * Returns the current scale value.
	 *
	 * @return the scale value, or 0.0 if the scale is null
	 */
	public double getScaleValue() { return Optional.ofNullable(this.scale).map(c -> c.toDouble(0)).orElse(0.0); }

	/**
	 * Returns the underlying {@link PackedCollection} object.
	 *
	 * @return the PackedCollection containing the scale value
	 */
	public PackedCollection<?> getScale() { return scale; }

	/**
	 * Returns a unique signature for this factor based on its scale value.
	 * <p>The signature is the hexadecimal representation of the scale value.
	 *
	 * @return the hexadecimal string representation of the scale
	 */
	@Override
	public String signature() {
		return Double.toHexString(scale.toDouble(0));
	}

	/**
	 * Returns a formatted string representation of the scale value.
	 *
	 * @return the formatted scale value
	 */
	@Override
	public String toString() { return NumberFormats.displayFormat.format(scale.toDouble(0)); }
}
