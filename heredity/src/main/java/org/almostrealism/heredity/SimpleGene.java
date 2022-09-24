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

package org.almostrealism.heredity;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.computations.StaticComputationAdapter;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.StaticCollectionComputation;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.PassThroughEvaluable;

import java.util.function.Supplier;

public class SimpleGene implements Gene<PackedCollection<?>>, GeneParameters, CollectionFeatures {
	public static final boolean enableComputation = false;

	private PackedCollection<?> values;

	public SimpleGene(int length) {
		this.values = new PackedCollection<>(length);
	}

	public void set(int index, double value) {
		values.setMem(index, value);
	}

	@Override
	public PackedCollection<?> getParameters() { return values; }

	@Override
	public Factor<PackedCollection<?>> valueAt(int pos) {
		if (enableComputation) {
			return new Factor<PackedCollection<?>>() {
				@Override
				public Producer<PackedCollection<?>> getResultant(Producer<PackedCollection<?>> value) {
					return _multiply(value, c((Supplier) () -> new Provider<>(values), pos));
				}

				@Override
				public String signature() {
					return Double.toHexString(values.toDouble(pos));
				}
			};
		} else {
			return new Factor<PackedCollection<?>>() {
				@Override
				public Producer<PackedCollection<?>> getResultant(Producer<PackedCollection<?>> value) {
					PackedCollection<?> result = new PackedCollection<>(1);

					if (value instanceof StaticCollectionComputation) {
						result.setMem(((StaticCollectionComputation) value).getValue().toDouble(0) * values.toDouble(pos));
					} else {
						result.setMem(value.get().evaluate().toDouble(0) * values.toDouble(pos));
					}

					return () -> args -> result;
				}

				@Override
				public String signature() {
					return Double.toHexString(values.toDouble(pos));
				}
			};
		}
	}

	@Override
	public int length() { return values.getMemLength(); }
}
