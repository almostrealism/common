/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.profile;

import java.util.function.Supplier;

@FunctionalInterface
public interface ScopeTimingListener {
	default <T> T recordDuration(String stage, Supplier<T> supplier) {
		return recordDuration(null, stage, supplier);
	}

	default <T> T recordDuration(OperationMetadata metadata, String stage, Supplier<T> supplier) {
		long start = System.nanoTime();

		try {
			return supplier.get();
		} finally {
			recordDuration(metadata, stage, System.nanoTime() - start);
		}
	}

	default void recordDuration(OperationMetadata metadata, String stage, long nanos) {
		recordDuration(metadata, metadata, stage, nanos);
	}

	void recordDuration(OperationMetadata root, OperationMetadata metadata, String stage, long nanos);
}
