/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.optimize;

import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

/**
 * A dataset that generates value targets on-the-fly from input data.
 * <p>
 * {@code FunctionalDataset} stores only the input data and applies a transformation
 * function to generate the corresponding targets when the dataset is iterated.
 * This is useful for:
 * </p>
 * <ul>
 *   <li>Data augmentation (generating multiple variants per input)</li>
 *   <li>Lazy evaluation (computing targets only when needed)</li>
 *   <li>Memory efficiency (not storing precomputed targets)</li>
 *   <li>Dynamic target generation (targets computed at runtime)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create functional dataset with data augmentation
 * List<PackedCollection> images = loadImages();
 * FunctionalDataset<PackedCollection> dataset = new FunctionalDataset<>(
 *     images,
 *     image -> {
 *         List<ValueTarget<PackedCollection>> augmented = new ArrayList<>();
 *         augmented.add(ValueTarget.of(image, computeLabel(image)));
 *         augmented.add(ValueTarget.of(flip(image), computeLabel(image)));
 *         augmented.add(ValueTarget.of(rotate(image), computeLabel(image)));
 *         return augmented;
 *     }
 * );
 *
 * // Iterate over all augmented samples
 * for (ValueTarget<PackedCollection> sample : dataset) {
 *     // Process sample...
 * }
 * }</pre>
 *
 * @param <T> the type of data in the value targets (typically {@link PackedCollection})
 *
 * @see Dataset
 * @see ValueTarget
 *
 * @author Michael Murray
 */
public class FunctionalDataset<T extends PackedCollection> implements Dataset<T> {
	private final List<PackedCollection> inputs;
	private final Function<PackedCollection, Collection<ValueTarget<T>>> function;

	/**
	 * Creates a functional dataset with the given inputs and transformation function.
	 *
	 * @param inputs   the list of input data items
	 * @param function a function that generates value targets from each input;
	 *                 may return multiple targets per input for data augmentation
	 */
	public FunctionalDataset(List<PackedCollection> inputs,
							 Function<PackedCollection, Collection<ValueTarget<T>>> function) {
		this.inputs = inputs;
		this.function = function;
	}

	/**
	 * Returns an iterator over the generated value targets.
	 * <p>
	 * The transformation function is applied lazily as the iterator is consumed.
	 * Each input may produce multiple value targets (for data augmentation).
	 * </p>
	 *
	 * @return an iterator over all generated value targets
	 */
	@Override
	public Iterator<ValueTarget<T>> iterator() {
		return inputs.stream()
				.map(function)
				.flatMap(Collection::stream)
				.iterator();
	}

	/**
	 * Splits the dataset by splitting the underlying input list.
	 * <p>
	 * This is more efficient than the default implementation because it
	 * creates two new functional datasets that share the same transformation
	 * function but operate on different subsets of inputs.
	 * </p>
	 *
	 * @param ratio the probability of an input being assigned to the first dataset
	 * @return a list of two functional datasets
	 */
	@Override
	public List<Dataset<T>> split(double ratio) {
		List<PackedCollection> a = new ArrayList<>();
		List<PackedCollection> b = new ArrayList<>();

		inputs.forEach(v -> {
			if (Math.random() < ratio) {
				a.add(v);
			} else {
				b.add(v);
			}
		});

		return List.of(new FunctionalDataset<>(a, function),
					   new FunctionalDataset<>(b, function));
	}
}
