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

import org.almostrealism.io.Console;

/**
 * A functional interface for listening to and recording the execution duration
 * of operations.
 *
 * <p>This listener is used throughout the profiling system to time the execution
 * of {@link Runnable} operations. It provides convenience methods that wrap a
 * runnable, measure its wall-clock execution time, and record the result.</p>
 *
 * <p>When the runnable implements {@link OperationInfo}, its metadata is automatically
 * extracted for the timing entry. Otherwise, a fallback metadata is created from
 * the class name.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * OperationTimingListener listener = profile.getRuntimeListener();
 * long nanos = listener.recordDuration(myRunnable);
 * }</pre>
 *
 * @see OperationProfile#getTimingListener()
 * @see OperationProfileNode#getRuntimeListener()
 * @see OperationMetadata
 *
 * @author Michael Murray
 */
@FunctionalInterface
public interface OperationTimingListener {
	/**
	 * Records the duration of the given runnable with no requester metadata.
	 *
	 * @param r the operation to time
	 * @return the elapsed time in nanoseconds
	 */
	default long recordDuration(Runnable r) {
		return recordDuration(null, r);
	}

	/**
	 * Measures the execution time of the given runnable and records it.
	 * If the runnable implements {@link OperationInfo}, its metadata is
	 * used; otherwise, a fallback metadata is created from the class name.
	 *
	 * @param requester the metadata of the requesting operation (may be {@code null})
	 * @param r         the operation to time
	 * @return the elapsed time in nanoseconds
	 */
	default long recordDuration(OperationMetadata requester, Runnable r) {
		long start = System.nanoTime();
		r.run();
		long end = System.nanoTime();

		OperationMetadata metadata = null;
		if (r instanceof OperationInfo) {
			metadata = ((OperationInfo) r).getMetadata();

			if (metadata == null) {
				Console.root().warn(r.getClass().getSimpleName() + " has no metadata");
			}
		}

		if (metadata == null) {
			metadata = new OperationMetadata(r.getClass().getSimpleName(), r.getClass().getSimpleName());
		}

		recordDuration(requester, metadata, end - start);
		return end - start;
	}

	/**
	 * Records an operation's execution duration.
	 *
	 * @param requesterMetadata  the metadata of the operation that initiated this execution
	 *                           (may be {@code null})
	 * @param operationMetadata  the metadata of the operation that was executed
	 * @param nanos              the execution time in nanoseconds
	 */
	void recordDuration(OperationMetadata requesterMetadata, OperationMetadata operationMetadata, long nanos);
}
