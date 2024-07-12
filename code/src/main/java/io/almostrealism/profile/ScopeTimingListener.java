package io.almostrealism.profile;

import io.almostrealism.code.OperationMetadata;

@FunctionalInterface
public interface ScopeTimingListener {
	void recordDuration(OperationMetadata root, OperationMetadata metadata, String stage, long nanos);
}
