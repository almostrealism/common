package io.almostrealism.profile;

import io.almostrealism.code.OperationMetadata;

@FunctionalInterface
public interface ScopeTimingListener {
	default void recordDuration(OperationMetadata metadata, String stage, long nanos) {
		recordDuration(metadata, metadata, stage, nanos);
	}

	void recordDuration(OperationMetadata root, OperationMetadata metadata, String stage, long nanos);
}
