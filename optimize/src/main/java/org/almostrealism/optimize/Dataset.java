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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * An iterable collection of input-output pairs for supervised learning.
 * <p>
 * A {@code Dataset} provides access to training examples through iteration over
 * {@link ValueTarget} pairs. It supports common data manipulation operations like
 * train/validation splitting and batching.
 * </p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Iteration over input-output pairs via {@link ValueTarget}</li>
 *   <li>Random train/validation splitting with configurable ratio</li>
 *   <li>Mini-batch creation for batch training</li>
 *   <li>Functional datasets for on-the-fly data generation</li>
 * </ul>
 *
 * <h2>Creating Datasets</h2>
 * <pre>{@code
 * // From a list of ValueTargets
 * List<ValueTarget<PackedCollection<?>>> pairs = new ArrayList<>();
 * pairs.add(ValueTarget.of(input1, target1));
 * pairs.add(ValueTarget.of(input2, target2));
 * Dataset<PackedCollection<?>> dataset = Dataset.of(pairs);
 *
 * // Functional dataset (on-the-fly generation)
 * Dataset<PackedCollection<?>> functional = Dataset.of(
 *     inputsList,
 *     input -> Collections.singletonList(ValueTarget.of(input, computeTarget(input)))
 * );
 * }</pre>
 *
 * <h2>Train/Validation Split</h2>
 * <pre>{@code
 * // 80% training, 20% validation
 * List<Dataset<PackedCollection<?>>> splits = dataset.split(0.8);
 * Dataset<PackedCollection<?>> trainSet = splits.get(0);
 * Dataset<PackedCollection<?>> validSet = splits.get(1);
 * }</pre>
 *
 * <h2>Batching</h2>
 * <pre>{@code
 * // Create batches of 32 samples
 * Dataset<PackedCollection<?>> batched = dataset.batch(32);
 * for (ValueTarget<PackedCollection<?>> batch : batched) {
 *     // batch.getInput() shape: [32, input_features...]
 *     // batch.getExpectedOutput() shape: [32, output_features...]
 * }
 * }</pre>
 *
 * @param <T> the type of data stored in the dataset (typically {@link PackedCollection})
 *
 * @see ValueTarget
 * @see FunctionalDataset
 * @see ModelOptimizer
 *
 * @author Michael Murray
 */
public interface Dataset<T extends MemoryData> extends Iterable<ValueTarget<T>> {

	/**
	 * Randomly splits the dataset into two parts based on the given ratio.
	 * <p>
	 * Each sample is randomly assigned to one of the two resulting datasets.
	 * Use this for creating train/validation or train/test splits.
	 * </p>
	 *
	 * @param ratio the probability of a sample being assigned to the first dataset
	 *              (e.g., 0.8 for 80% train, 20% validation)
	 * @return a list of two datasets: [first, second]
	 */
	default List<Dataset<T>> split(double ratio) {
		List<ValueTarget<T>> a = new ArrayList<>();
		List<ValueTarget<T>> b = new ArrayList<>();

		forEach(v -> {
			if (Math.random() < ratio) {
				a.add(v);
			} else {
				b.add(v);
			}
		});

		return List.of(of(a), of(b));
	}

	/**
	 * Creates a batched version of this dataset.
	 * <p>
	 * Combines consecutive samples into batches of the specified size.
	 * The resulting dataset yields batched tensors where the first dimension
	 * is the batch size.
	 * </p>
	 *
	 * @param batchSize the number of samples per batch
	 * @return a new dataset yielding batched samples
	 */
	default Dataset<PackedCollection<?>> batch(int batchSize) {
		return batches(batchSize, (Iterable) this);
	}

	/**
	 * Creates a dataset from an iterable of value targets.
	 *
	 * @param <T>     the type of data in the value targets
	 * @param targets the iterable of input-output pairs
	 * @return a new dataset wrapping the targets
	 */
	static <T extends MemoryData> Dataset<T> of(Iterable<ValueTarget<T>> targets) {
		return () -> targets.iterator();
	}

	/**
	 * Creates a functional dataset that generates targets on-the-fly.
	 * <p>
	 * This is useful when target computation is expensive or when data
	 * augmentation is needed.
	 * </p>
	 *
	 * @param <T>      the type of data in the value targets
	 * @param inputs   the input data items
	 * @param function a function that generates value targets from an input
	 * @return a new functional dataset
	 */
	static <T extends PackedCollection<?>> FunctionalDataset<T> of(Iterable<PackedCollection<?>> inputs,
														  Function<PackedCollection<?>, Collection<ValueTarget<T>>> function) {
		List<PackedCollection<?>> list = new ArrayList<>();
		inputs.forEach(list::add);
		return new FunctionalDataset(list, function);
	}

	/**
	 * Creates a batched dataset from individual value targets.
	 * <p>
	 * Consecutive samples are combined into batches. The input and output
	 * tensors have their shapes prepended with the batch dimension.
	 * </p>
	 *
	 * @param <T>       the type of data in the value targets
	 * @param batchSize the number of samples per batch
	 * @param targets   the iterable of individual value targets
	 * @return a dataset yielding batched value targets
	 */
	static <T extends PackedCollection<?>> Dataset<PackedCollection<?>> batches(int batchSize, Iterable<ValueTarget<T>> targets) {
		TraversalPolicy inputItem;
		TraversalPolicy targetItem;
		List<ValueTarget<PackedCollection<?>>> data = new ArrayList<>();

		int n = -1;
		PackedCollection<PackedCollection<?>> currentInput = null;
		PackedCollection<PackedCollection<?>> currentTarget = null;

		f: for (ValueTarget<T> target : targets) {
			if (n < 0) {
				inputItem = target.getInput().getShape();
				currentInput = new PackedCollection<>(inputItem.prependDimension(batchSize).traverse(1));

				targetItem = target.getExpectedOutput().getShape();
				currentTarget = new PackedCollection<>(targetItem.prependDimension(batchSize).traverse(1));

				n = 0;
			}

			currentInput.set(n, target.getInput());
			currentTarget.set(n, target.getExpectedOutput());
			n++;

			if (n >= batchSize) {
				data.add(ValueTarget.of(currentInput, currentTarget));
				currentInput = new PackedCollection<>(currentInput.getShape());
				currentTarget = new PackedCollection<>(currentTarget.getShape());
				n = 0;
			}
		}

		return Dataset.of(data);
	}
}
