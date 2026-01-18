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
import org.almostrealism.hardware.MemoryData;

/**
 * Represents an input-output pair for supervised learning.
 * <p>
 * A {@code ValueTarget} encapsulates a single training example consisting of
 * an input tensor and its corresponding expected output (target/label).
 * These pairs are the fundamental unit of data for training neural networks.
 * </p>
 *
 * <h2>Components</h2>
 * <ul>
 *   <li><b>Input</b>: The data fed to the model (features, images, sequences, etc.)</li>
 *   <li><b>Expected Output</b>: The target value the model should predict</li>
 *   <li><b>Arguments</b>: Optional additional inputs (for multi-input models)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a simple input-output pair
 * PackedCollection input = loadImage();
 * PackedCollection label = oneHotEncode(classIndex);
 * ValueTarget<PackedCollection> sample = ValueTarget.of(input, label);
 *
 * // Access during training
 * PackedCollection x = sample.getInput();
 * PackedCollection y = sample.getExpectedOutput();
 *
 * // With additional arguments for multi-input models
 * ValueTarget<PackedCollection> multiInput = sample.withArguments(mask, positions);
 * }</pre>
 *
 * @param <T> the type of data stored (typically {@link PackedCollection})
 *
 * @see Dataset
 * @see ModelOptimizer
 *
 * @author Michael Murray
 */
public interface ValueTarget<T extends MemoryData> {
	/**
	 * Returns the input data for this training example.
	 *
	 * @return the input tensor
	 */
	PackedCollection getInput();

	/**
	 * Returns additional arguments for multi-input models.
	 * <p>
	 * Override this method to provide additional inputs beyond the main input,
	 * such as attention masks, positional encodings, or context vectors.
	 * </p>
	 *
	 * @return an array of additional input tensors; empty array by default
	 */
	default PackedCollection[] getArguments() {
		return new PackedCollection[0];
	}

	/**
	 * Returns the expected output (target) for this training example.
	 *
	 * @return the target tensor
	 */
	PackedCollection getExpectedOutput();

	/**
	 * Creates a new value target with additional arguments.
	 * <p>
	 * This is useful for models that require multiple inputs, allowing
	 * you to add auxiliary inputs while preserving the original input
	 * and expected output.
	 * </p>
	 *
	 * @param <V>  the type parameter for the new value target
	 * @param args the additional arguments to include
	 * @return a new value target with the specified arguments
	 */
	default <V extends PackedCollection> ValueTarget<V> withArguments(PackedCollection... args) {
		ValueTarget<T> original = this;

		return new ValueTarget<>() {
			@Override
			public PackedCollection getInput() {
				return original.getInput();
			}

			@Override
			public PackedCollection[] getArguments() {
				return args;
			}

			@Override
			public PackedCollection getExpectedOutput() {
				return original.getExpectedOutput();
			}
		};
	}

	/**
	 * Creates a value target from an input and expected output.
	 *
	 * @param <T>            the type of data
	 * @param input          the input tensor
	 * @param expectedOutput the target tensor
	 * @return a new value target wrapping the input-output pair
	 */
	static <T extends MemoryData> ValueTarget<T> of(PackedCollection input, PackedCollection expectedOutput) {
		return new ValueTarget<T>() {
			@Override
			public PackedCollection getInput() {
				return input;
			}

			@Override
			public PackedCollection getExpectedOutput() {
				return expectedOutput;
			}
		};
	}
}
