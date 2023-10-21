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
import io.almostrealism.code.OperationMetadata;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeInstructionSet;

public abstract class BaseGeneratedOperation<T extends MemoryData> implements NativeInstructionSet {
	private ComputeContext<MemoryData> context;
	private OperationMetadata metadata;

	public BaseGeneratedOperation(Computation<T> computation) { }

	@Override
	public ComputeContext<MemoryData> getComputeContext() { return context; }

	@Override
	public void setComputeContext(ComputeContext<MemoryData> context) { this.context = context; }

	@Override
	public OperationMetadata getMetadata() {
		return metadata;
	}

	@Override
	public void setMetadata(OperationMetadata metadata) {
		this.metadata = metadata;
	}
}
