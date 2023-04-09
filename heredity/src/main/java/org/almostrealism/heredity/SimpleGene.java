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
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.StaticCollectionComputation;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class SimpleGene implements Gene<PackedCollection<?>>, GeneParameters, CollectionFeatures {
	public static final boolean enableComputation = true;
	public static final boolean enableShortCircuit = true;

	private PackedCollection<?> values;
	private UnaryOperator<Producer<PackedCollection<?>>> transform;

	public SimpleGene(int length) {
		this.values = new PackedCollection<>(length);
	}

	public void set(int index, double value) {
		values.setMem(index, value);
	}

	public UnaryOperator<Producer<PackedCollection<?>>> getTransform() {
		return transform;
	}

	public void setTransform(UnaryOperator<Producer<PackedCollection<?>>> transform) {
		this.transform = transform;
	}

	@Override
	public PackedCollection<?> getParameters() { return values; }

	@Override
	public Factor<PackedCollection<?>> valueAt(int pos) {
		if (enableShortCircuit) {
			return new Factor<PackedCollection<?>>() {
				@Override
				public Producer<PackedCollection<?>> getResultant(Producer<PackedCollection<?>> value) {
					return transform(multiply(value, c((Supplier) () -> new Provider<>(values), pos), args -> {
						PackedCollection<?> result = new PackedCollection<>(1);

						if (value instanceof StaticCollectionComputation) {
							result.setMem(((StaticCollectionComputation) value).getValue().toDouble(0) * values.toDouble(pos));
						} else {
							result.setMem(value.get().evaluate(args).toDouble(0) * values.toDouble(pos));
						}

						return result;
					}));
				}

				@Override
				public String signature() {
					return Double.toHexString(values.toDouble(pos));
				}
			};
		} else if (enableComputation) {
			return new Factor<PackedCollection<?>>() {
				@Override
				public Producer<PackedCollection<?>> getResultant(Producer<PackedCollection<?>> value) {
					return transform(multiply(value, c((Supplier) () -> new Provider<>(values), pos)));
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

					return transform(() -> args -> result);
				}

				@Override
				public String signature() {
					return Double.toHexString(values.toDouble(pos));
				}
			};
		}
	}

	protected Producer<PackedCollection<?>> transform(Producer<PackedCollection<?>> value) {
		return transform == null ? value : transform.apply(value);
	}

	@Override
	public int length() { return values.getMemLength(); }
}
