/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.generated;

import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeInstructionSet;

/**
 * Base class for generated native operations. Provides common infrastructure
 * for managing compute context, operation metadata, and parallelism settings
 * required by {@link NativeInstructionSet} implementations.
 *
 * @param <T> the type of memory data this operation works with
 */
public abstract class BaseGeneratedOperation<T extends MemoryData> implements NativeInstructionSet {
	/** The compute context for kernel execution. */
	private ComputeContext<MemoryData> context;
	/** Operation metadata for profiling and identification. */
	private OperationMetadata metadata;
	/** Number of parallel execution units to use. */
	private int parallelism;

	/**
	 * Constructs a new generated operation based on the specified computation.
	 * Initializes parallelism to 1 (single-threaded execution).
	 *
	 * @param computation the computation this operation is generated from
	 */
	public BaseGeneratedOperation(Computation<T> computation) {
		parallelism = 1;
	}

	/** Returns the compute context for this operation. */
	@Override
	public ComputeContext<MemoryData> getComputeContext() { return context; }

	/**
	 * Sets the compute context for this operation.
	 *
	 * @param context the compute context to use for execution
	 */
	@Override
	public void setComputeContext(ComputeContext<MemoryData> context) { this.context = context; }

	/** Returns the metadata describing this operation. */
	@Override
	public OperationMetadata getMetadata() {
		return metadata;
	}

	/**
	 * Sets the metadata for this operation.
	 *
	 * @param metadata the operation metadata containing profiling and identification information
	 */
	@Override
	public void setMetadata(OperationMetadata metadata) {
		this.metadata = metadata;
	}

	/** Returns the parallelism level for this operation. */
	@Override
	public int getParallelism() { return parallelism; }

	/**
	 * Sets the parallelism level for this operation.
	 *
	 * @param parallelism the number of parallel execution units to use
	 */
	@Override
	public void setParallelism(int parallelism) { this.parallelism = parallelism; }
}
