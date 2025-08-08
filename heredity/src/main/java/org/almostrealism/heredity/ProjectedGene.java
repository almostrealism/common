/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Factor;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.Random;
import java.util.stream.IntStream;

public class ProjectedGene extends TransformableGene implements VectorFeatures {
	private final PackedCollection<?> source;
	private final PackedCollection<?> weights;
	private final PackedCollection<?> ranges;

	private final PackedCollection<?> values;

	public ProjectedGene(PackedCollection<?> source,
						 PackedCollection<?> weights) {
		super(weights.getShape().length(0));
		this.source = source;
		this.weights = weights;
		this.ranges = new PackedCollection<>(shape(length(), 2)).traverse(1);
		this.values = new PackedCollection<>(shape(length()));

		int sourceLength = source.getShape().length(0);

		if (source.getShape().getDimensions() != 1 ||
				weights.getShape().getDimensions() != 2 ||
				weights.getShape().length(1) != sourceLength ||
				weights.getAtomicMemLength() != sourceLength) {
			throw new IllegalArgumentException();
		}

		initRanges();
	}

	public void initWeights(long seed) {
		randn(shape(weights), new Random(seed)).into(weights).evaluate();
		for (int pos = 0; pos < length(); pos++) {
			double scale = Math.sqrt(weights.get(pos).doubleStream().map(d -> d * d).sum());
			weights.get(pos).setMem(weights.get(pos).doubleStream().map(d -> d / scale).toArray());
		}
	}

	public void refreshValues() {
		for (int pos = 0; pos < length(); pos++) {
			int p = pos;
			double start = ranges.get(pos).toDouble(0);
			double range = ranges.get(pos).toDouble(1) - start;
			double value = IntStream.range(0, source.getMemLength())
					.mapToDouble(i -> source.toDouble(i) * weights.valueAt(p, i))
					.sum();
			values.setMem(pos, start + value * range);
		}
	}

	public TraversalPolicy getInputShape() { return source.getShape(); }

	protected void initRanges() {
		for (int i = 0; i < length(); i++) {
			ranges.get(i).setMem(0.0, 1.0);
		}
	}

	public void setRange(int index, double min, double max) {
		ranges.get(index).setMem(min, max);
	}

	@Override
	public Factor<PackedCollection<?>> valueAt(int pos) {
		return in ->
				transform(pos, cp(values.range(shape(1), pos)));
	}

	@Override
	public int length() { return weights.getShape().length(0); }
}
