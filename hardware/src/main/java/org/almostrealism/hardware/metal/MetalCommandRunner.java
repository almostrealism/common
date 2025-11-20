/*
 * Copyright 2025 Michael Murray
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

/**
 * Executor service for {@link MetalCommand} instances.
 *
 * <p>Manages pre-allocated offset and size buffers for kernel arguments and
 * submits commands to a single-threaded executor.</p>
 *
 * <h2>Buffer Management</h2>
 *
 * <pre>{@code
 * // Pre-allocated buffers avoid repeated allocation overhead
 * MTLBuffer offset;  // int[MAX_ARGS] for argument offsets
 * MTLBuffer size;    // int[MAX_ARGS] for argument sizes
 *
 * // Commands reuse these buffers
 * offset.setContents(new int[]{0, 10, 20});
 * size.setContents(new int[]{10, 10, 10});
 * }</pre>
 *
 * @see MetalCommand
 * @see MetalOperator
 */
public class MetalCommandRunner {
	public static final int MAX_ARGS = 512;

	private ExecutorService executor;

	private MTLBuffer offset;
	private MTLBuffer size;
	private final MTLCommandQueue queue;

	public MetalCommandRunner(MTLCommandQueue queue) {
		this.executor = Executors.newSingleThreadExecutor();
		this.queue = queue;
		offset = queue.getDevice().newIntBuffer32(MAX_ARGS);
		size = queue.getDevice().newIntBuffer32(MAX_ARGS);
	}

	public Future<?> submit(MetalCommand command) {
		return executor.submit(() -> command.run(offset, size, queue));
	}

	public void destroy() {
		if (offset != null) offset.release();
		if (size != null) size.release();
		if (executor != null) executor.shutdown();
		offset = null;
		size = null;
		executor = null;
	}
}
