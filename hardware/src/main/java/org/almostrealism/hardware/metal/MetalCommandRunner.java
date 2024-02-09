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

package org.almostrealism.hardware.metal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public class MetalCommandRunner {
	public static final int MAX_ARGS = 512;

	private ExecutorService executor;

	private MTLBuffer offset;
	private MTLBuffer size;
	private MTLBuffer dim0;
	private final MTLCommandQueue queue;

	public MetalCommandRunner(MTLCommandQueue queue) {
		this.executor = Executors.newSingleThreadExecutor();
		this.queue = queue;
		offset = queue.getDevice().newIntBuffer32(MAX_ARGS);
		size = queue.getDevice().newIntBuffer32(MAX_ARGS);
		dim0 = queue.getDevice().newIntBuffer32(MAX_ARGS);
	}

	public Future<?> submit(MetalCommand command) {
		return executor.submit(() -> command.run(offset, size, dim0, queue));
	}

	public void destroy() {
		if (offset != null) offset.release();
		if (size != null) size.release();
		if (dim0 != null) dim0.release();
		if (executor != null) executor.shutdown();
		offset = null;
		size = null;
		dim0 = null;
		executor = null;
	}
}
