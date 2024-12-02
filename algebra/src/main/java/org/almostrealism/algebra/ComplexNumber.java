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

public class ComplexNumber extends Pair {
	public ComplexNumber() {
	}

	public ComplexNumber(MemoryData delegate, int delegateOffset) {
		super(delegate, delegateOffset);
	}

	public ComplexNumber(double x, double y) {
		super(x, y);
	}

	public double getRealPart() { return left(); }
	public double getImaginaryPart() { return right(); }
	public double r() { return getRealPart(); }
	public double i() { return getImaginaryPart(); }

	public static BiFunction<MemoryData, Integer, PackedCollection<Pair<?>>> complexPostprocessor() {
		return (delegate, offset) -> {
			TraversalPolicy shape = CollectionFeatures.getInstance().shape(delegate);

			if (shape.getTotalSize() == 2 || offset != 0) {
				return new ComplexNumber(delegate, offset);
			} else {
				return new PackedCollection<>(shape, 1,
						spec -> new ComplexNumber(spec.getDelegate(), spec.getOffset()),
						delegate, offset);
			}
		};
	}
}
