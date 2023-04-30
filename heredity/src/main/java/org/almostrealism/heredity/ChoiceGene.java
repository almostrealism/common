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

import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

public class ChoiceGene implements Gene<PackedCollection<?>>, GeneParameters, ScalarFeatures, CollectionFeatures {
	private PackedCollection<?> choices;
	private PackedCollection<?> values;

	public ChoiceGene(PackedCollection<?> choices, int length) {
		this.choices = choices;
		this.values = new PackedCollection<>(length);
	}

	public void set(int index, double value) {
		values.setMem(index, value);
	}

	@Override
	public PackedCollection<?> getParameters() { return values; }

	@Override
	public PackedCollection<?> getParameterRanges() {
		PackedCollection<?> ranges = new PackedCollection<>(shape(values.getMemLength(), 2), 1);

		for (int i = 0; i < values.getMemLength(); i++) {
			ranges.get(i).setMem(0.0, 1.0);
		}

		return ranges;
	}

	@Override
	public Factor<PackedCollection<?>> valueAt(int pos) {
		return new Factor<>() {
			@Override
			public Producer<PackedCollection<?>> getResultant(Producer<PackedCollection<?>> value) {
				value = c(shape(1), p(values), scalar(pos));
				return c(shape(1), p(choices), (Supplier) multiply(value, c(choices.getMemLength())));
			}

			@Override
			public String signature() {
				return Double.toHexString(values.toDouble(pos));
			}
		};
	}

	@Override
	public int length() { return values.getMemLength(); }
}
