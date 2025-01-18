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

import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;

@FunctionalInterface
public interface OperationTimingListener {
	default long recordDuration(Runnable r) {
		return recordDuration(null, r);
	}

	default long recordDuration(OperationMetadata requester, Runnable r) {
		long start = System.nanoTime();
		r.run();
		long end = System.nanoTime();

		OperationMetadata metadata = null;
		if (r instanceof OperationInfo) {
			metadata = ((OperationInfo) r).getMetadata();

			if (metadata == null) {
				System.out.println("Warning: " + r.getClass().getSimpleName() + " has no metadata");
			}
		}

		if (metadata == null) {
			metadata = new OperationMetadata(r.getClass().getSimpleName(), r.getClass().getSimpleName());
		}

		recordDuration(requester, metadata, end - start);
		return end - start;
	}

	void recordDuration(OperationMetadata requesterMetadata, OperationMetadata operationMetadata, long nanos);
}
