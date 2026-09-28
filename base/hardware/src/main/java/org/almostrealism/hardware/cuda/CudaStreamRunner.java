/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.cuda;

import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.streams.Semaphore;

import java.util.function.Consumer;

/**
 * Submits work to the {@link CUStream} of one {@link CudaComputeContext}.
 *
 * <p>This implementation is synchronous: {@link #submit} waits for any dependency on the
 * host, enqueues the work, waits for the stream to drain, and runs the completion callback
 * before returning. It therefore always returns {@code null}, meaning there is nothing left
 * to wait for. Submissions are serialized so that work from different threads is never
 * interleaved on the stream.</p>
 */
public class CudaStreamRunner {
	/** The stream all work is submitted to. */
	private final CUStream stream;

	/**
	 * Creates a runner for the given stream, which it takes ownership of.
	 *
	 * @param stream the stream to submit work to
	 */
	public CudaStreamRunner(CUStream stream) {
		this.stream = stream;
	}

	/** Returns the stream work is submitted to. */
	public CUStream getStream() { return stream; }

	/**
	 * Runs {@code command} against the stream once {@code dependsOn} has completed.
	 *
	 * @param requester  the operation submitting the work
	 * @param command    enqueues the work on the stream
	 * @param dependsOn  work that must complete first, or null
	 * @param onComplete run once the work has completed (or failed), or null
	 * @return a semaphore for the work's completion, or null if it has already completed
	 */
	public synchronized Semaphore submit(OperationMetadata requester, Consumer<CUStream> command,
										 Semaphore dependsOn, Runnable onComplete) {
		try {
			if (dependsOn != null) dependsOn.waitFor();
			command.accept(stream);
			stream.synchronize();
		} finally {
			if (onComplete != null) onComplete.run();
		}

		return null;
	}

	/** Waits for all submitted work and releases the stream. */
	public synchronized void destroy() {
		if (stream.isReleased()) return;
		stream.synchronize();
		stream.release();
	}
}
