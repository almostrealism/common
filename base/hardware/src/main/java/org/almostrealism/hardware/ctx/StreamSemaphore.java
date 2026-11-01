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

package org.almostrealism.hardware.ctx;

import io.almostrealism.concurrent.OperationSemaphore;
import io.almostrealism.profile.OperationMetadata;

/**
 * The completion handle a {@link SubmissionStream} returns for one submission, attributed to the
 * operation that made it: the part of every backend's completion that is the same whatever the
 * backend observes to know the work has finished.
 *
 * <p>It holds the {@link #getRequester() requester} the completion is attributed to, the
 * {@link #getStream() stream} the submission was made to, typed as the backend's runner, and the
 * submission's {@link #getOrdinal() ordinal} in that stream. A backend supplies how the completion
 * is waited for and observed ({@link #waitFor()}, {@link #onSettled(Runnable)}), how two of its
 * completions merge, and how it is re-attributed ({@link #withRequester(OperationMetadata)}).</p>
 *
 * @param <S> the type of the stream the submission was made to
 */
public abstract class StreamSemaphore<S extends SubmissionStream<?>> implements OperationSemaphore, StreamCompletion {
	/** Metadata identifying the operation this completion belongs to, or {@code null}. */
	private final OperationMetadata requester;

	/** The stream the submission was made to. */
	private final S stream;

	/** The position of the submission in {@link #stream}'s order. */
	private final long ordinal;

	/**
	 * Creates the completion of one submission.
	 *
	 * @param requester metadata of the operation this completion belongs to, or {@code null}
	 * @param stream    the stream the submission was made to
	 * @param ordinal   the position of the submission in the stream's order
	 */
	protected StreamSemaphore(OperationMetadata requester, S stream, long ordinal) {
		this.requester = requester;
		this.stream = stream;
		this.ordinal = ordinal;
	}

	@Override
	public OperationMetadata getRequester() { return requester; }

	/** Returns the stream the submission was made to: the backend's runner. */
	@Override
	public S getStream() { return stream; }

	@Override
	public long getOrdinal() { return ordinal; }
}
