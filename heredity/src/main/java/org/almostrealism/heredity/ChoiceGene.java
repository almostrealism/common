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
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Signature;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

public class ChoiceGene implements Gene<PackedCollection<?>>, GeneParameters, ScalarFeatures, CollectionFeatures {
	private PackedCollection<?> choices;
	private Gene<PackedCollection<?>> values;

	public ChoiceGene(Gene<PackedCollection<?>> values, PackedCollection<?> choices) {
		this.choices = choices;
		this.values = values;
	}

	@Override
	public PackedCollection<?> getParameters() {
		return ((GeneParameters) values).getParameters();
	}

	@Override
	public PackedCollection<?> getParameterRanges() {
		return ((GeneParameters) values).getParameterRanges();
	}

	@Override
	public Factor<PackedCollection<?>> valueAt(int pos) {
		return new Factor<>() {
			@Override
			public Producer<PackedCollection<?>> getResultant(Producer<PackedCollection<?>> value) {
				value = values.valueAt(pos).getResultant(value);
				return c(shape(1), p(choices), multiply(value, c(choices.getMemLength())));
			}

			@Override
			public String signature() {
				return Signature.of(values.valueAt(pos));
			}
		};
	}

	@Override
	public int length() { return values.length(); }
}
