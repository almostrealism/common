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

/**
 * A functional interface for listening to and recording compilation stage
 * durations within the scope of an operation.
 *
 * <p>Compilation of an operation proceeds through multiple stages (e.g.,
 * "optimize", "generate", "compile"). This listener records the duration of
 * each stage, enabling fine-grained analysis of where compilation time is
 * spent.</p>
 *
 * <p>The listener supports two modes via
 * {@link OperationProfileNode#getScopeListener(boolean)}:</p>
 * <ul>
 *   <li><b>Exclusive</b> &mdash; each stage duration is recorded as a separate
 *       entry in the node's primary metric, suitable for non-overlapping stages</li>
 *   <li><b>Non-exclusive</b> &mdash; durations are recorded in the node's
 *       stage detail metric, suitable for overlapping or nested stages</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * ScopeTimingListener listener = profile.getScopeListener(true);
 * Scope result = listener.recordDuration(metadata, "optimize", () -> {
 *     return optimizer.optimize(scope);
 * });
 * }</pre>
 *
 * @see OperationProfile#getScopeListener(boolean)
 * @see OperationProfileNode#getScopeListener(boolean)
 *
 * @author Michael Murray
 */
@FunctionalInterface
public interface ScopeTimingListener {
	/**
	 * Times the given supplier and records the duration for the named stage
	 * with no associated metadata.
	 *
	 * @param <T>      the return type
	 * @param stage    the compilation stage name
	 * @param supplier the code to time
	 * @return the result from the supplier
	 */
	default <T> T recordDuration(String stage, Supplier<T> supplier) {
		return recordDuration(null, stage, supplier);
	}

	/**
	 * Times the given supplier and records the duration for the named stage
	 * under the given metadata.
	 *
	 * @param <T>      the return type
	 * @param metadata the metadata of the operation being compiled
	 * @param stage    the compilation stage name
	 * @param supplier the code to time
	 * @return the result from the supplier
	 */
	default <T> T recordDuration(OperationMetadata metadata, String stage, Supplier<T> supplier) {
		long start = System.nanoTime();

		try {
			return supplier.get();
		} finally {
			recordDuration(metadata, stage, System.nanoTime() - start);
		}
	}

	/**
	 * Records a stage duration using the same metadata as both root and detail.
	 *
	 * @param metadata the operation metadata
	 * @param stage    the compilation stage name
	 * @param nanos    the duration in nanoseconds
	 */
	default void recordDuration(OperationMetadata metadata, String stage, long nanos) {
		recordDuration(metadata, metadata, stage, nanos);
	}

	/**
	 * Records a compilation stage duration.
	 *
	 * @param root     the root operation metadata (used to locate the profile node)
	 * @param metadata the metadata of the specific operation stage
	 * @param stage    the compilation stage name (e.g., "optimize", "generate")
	 * @param nanos    the duration in nanoseconds
	 */
	void recordDuration(OperationMetadata root, OperationMetadata metadata, String stage, long nanos);
}
