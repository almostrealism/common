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

public interface ValueTarget<T extends MemoryData> {
	PackedCollection<T> getInput();

	default PackedCollection<?>[] getArguments() {
		return new PackedCollection[0];
	}

	PackedCollection<T> getExpectedOutput();

	static <T extends MemoryData> ValueTarget<T> of(PackedCollection<?> input, PackedCollection<?> expectedOutput) {
		return new ValueTarget<T>() {
			@Override
			public PackedCollection<T> getInput() {
				return (PackedCollection<T>) input;
			}

			@Override
			public PackedCollection<T> getExpectedOutput() {
				return (PackedCollection<T>) expectedOutput;
			}
		};
	}
}
