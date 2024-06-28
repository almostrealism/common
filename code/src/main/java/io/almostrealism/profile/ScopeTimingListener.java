package io.almostrealism.profile;

import io.almostrealism.code.OperationMetadata;

public interface ScopeTimingListener {
	void recordDuration(OperationMetadata root, OperationMetadata metadata, long nanos);
}
