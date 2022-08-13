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

import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;

public class AssignableGenome extends Tensor<PackedCollection<?>> implements Genome<PackedCollection<?>>, Delegated<Tensor<PackedCollection<?>>>, ScalarFeatures, CollectionFeatures {
	private final Tensor<PackedCollection<?>> delegate;
	private final int count;

	public AssignableGenome() {
		delegate = null;
		count = -1;
	}

	protected AssignableGenome(Tensor<PackedCollection<?>> delegate, int count) {
		this.delegate = delegate;
		this.count = count;
	}

	public void assignTo(Genome genome) {
		for (int x = 0; x < genome.count(); x++) {
			Chromosome<PackedCollection<?>> c = (Chromosome<PackedCollection<?>>) genome.valueAt(x);

			for (int y = 0; y < c.length(); y++) {
				Gene<PackedCollection<?>> g = c.valueAt(y);

				for (int z = 0; z < g.length(); z++) {
					Scalar v;

					if (g.valueAt(z) instanceof ScaleFactor) {
						v = ((ScaleFactor) g.valueAt(z)).getScale();
					} else {
						v = new Scalar(g.valueAt(z).getResultant(c(1.0)).get().evaluate().toDouble(0));
					}

					if (v == null) {
						throw new IllegalArgumentException();
					}

					if (get(x, y, z) == null) {
						insert(new Scalar(), x, y, z);
					}

					get(x, y, z).setMem(v.getValue());
				}
			}
		}
	}

	@Override
	public Genome getHeadSubset() {
		return new AssignableGenome(this, count() - 1);
	}

	@Override
	public Chromosome getLastChromosome() {
		return new AssignableChromosome(count() - 1);
	}

	@Override
	public PackedCollection<?> get(int... loc) {
		if (delegate != null) return delegate.get(loc);
		return super.get(loc);
	}

	@Override
	public int length(int... loc) {
		if (delegate != null) return delegate.length(loc);
		return super.length(loc);
	}

	@Override
	public Chromosome<PackedCollection<?>> valueAt(int pos) {
		return new AssignableChromosome(pos);
	}

	@Override
	public int count() { return count >= 0 ? count : length(); }

	@Override
	public Tensor<PackedCollection<?>> getDelegate() {
		return delegate;
	}

	protected class AssignableChromosome implements Chromosome<PackedCollection<?>> {
		private final int index;

		public AssignableChromosome(int index) {
			this.index = index;
		}

		@Override
		public int length() {
			return AssignableGenome.this.length(index);
		}

		@Override
		public Gene<PackedCollection<?>> valueAt(int pos) {
			return new AssignableGene(index, pos);
		}
	}

	protected class AssignableGene implements Gene<PackedCollection<?>> {
		private final int chromosome;
		private final int index;

		public AssignableGene(int chromosome, int index) {
			this.chromosome = chromosome;
			this.index = index;
		}

		@Override
		public int length() {
			return AssignableGenome.this.length(chromosome, index);
		}

		@Override
		public Factor<PackedCollection<?>> valueAt(int pos) {
			return value -> {
				PackedCollection<?> v = AssignableGenome.this.get(chromosome, index, pos);
				if (v == null) {
					throw new NullPointerException();
				}

				return _multiply(() -> new Provider<>(v), value);
			};
		}
	}
}
