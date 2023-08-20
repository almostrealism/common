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

package org.almostrealism.hardware.metal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

public class MetalCommandRunner {
	private ExecutorService executor;

	private final MTLCommandBuffer buffer;
	private final MTLComputeCommandEncoder encoder;

	public MetalCommandRunner(MTLCommandBuffer buffer) {
		this.executor = Executors.newSingleThreadExecutor();
		this.buffer = buffer;
		this.encoder = buffer.encoder();
	}

	public Future<?> submit(BiConsumer<MTLCommandBuffer, MTLComputeCommandEncoder> command) {
		return executor.submit(() -> command.accept(buffer, encoder));
	}
}
