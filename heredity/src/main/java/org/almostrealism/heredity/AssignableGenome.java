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
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;

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

	public String getSerialized() throws IOException {
		try (ByteArrayOutputStream data = new ByteArrayOutputStream(); DataOutputStream w = new DataOutputStream(data)) {
			for (int x = 0; x < length(); x++) {
				for (int y = 0; y < length(x); y++) {
					for (int z = 0; z < length(x, y); z++) {
						w.writeInt(x);
						w.writeInt(y);
						w.writeInt(z);
						w.writeDouble(get(x, y, z).toDouble(0));
					}
				}
			}

			w.flush();
			return Base64.getEncoder().encodeToString(data.toByteArray());
		}
	}

	public void setSerialized(String serialized) throws IOException {
		try (ByteArrayInputStream data = new ByteArrayInputStream(Base64.getDecoder().decode(serialized));
			 	DataInputStream in = new DataInputStream(data)) {
			while (in.available() > 0) {
				int x = in.readInt();
				int y = in.readInt();
				int z = in.readInt();
				double v = in.readDouble();
				insert(new Scalar(v), x, y, z);
			}
		}
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
			return new AssignableFactor(chromosome, index, pos);
		}
	}

	protected class AssignableFactor implements Factor<PackedCollection<?>> {
		private final int chromosome;
		private final int index;
		private final int pos;

		public AssignableFactor(int chromosome, int index, int pos) {
			this.chromosome = chromosome;
			this.index = index;
			this.pos = pos;
		}

		@Override
		public Producer<PackedCollection<?>> getResultant(Producer<PackedCollection<?>> value) {
			PackedCollection<?> v = getValue();
			if (v == null) {
				throw new NullPointerException();
			}

			return multiply(c(v.getShape(), p(v), c(0)), value);
		}

		public PackedCollection<?> getValue() {
			return AssignableGenome.this.get(chromosome, index, pos);
		}

		@Override
		public String signature() {
			return Double.toHexString(getValue().toDouble(0));
		}
	}
}
