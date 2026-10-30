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

import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.HardwareException;

/**
 * The completion of one submission to a {@link SubmissionStream}: a {@link Semaphore} that knows
 * the stream it belongs to and its position in that stream's order.
 *
 * <p>Knowing the stream is what lets a dependent submission to the same stream skip any host-side
 * ordering (see {@link SubmissionStream#ordersAfter(Semaphore)}); knowing the ordinal is what lets
 * two completions of one stream merge into the later of them
 * (see {@link #later(Semaphore)}) and what a
 * {@link FailureRecord} is keyed by.</p>
 */
public interface StreamCompletion extends Semaphore {
	/**
	 * Returns the stream this completion's submission was made to.
	 *
	 * @return the owning stream
	 */
	SubmissionStream<?> getStream();

	/**
	 * Returns the position of this completion's submission in its stream's order. A completion
	 * with a higher ordinal settles no earlier than one with a lower ordinal of the same stream.
	 *
	 * @return the submission ordinal
	 */
	long getOrdinal();

	/**
	 * Returns whether this completion's submission was made after {@code other}'s, so that it
	 * settles no earlier. Meaningful only for two completions of the same stream.
	 *
	 * @param other another completion of the same stream
	 * @return true if this completion has the higher ordinal
	 */
	default boolean follows(StreamCompletion other) {
		return getOrdinal() > other.getOrdinal();
	}

	/**
	 * Returns whichever of this completion and {@code other} was submitted later, or {@code null}
	 * if {@code other} does not belong to this completion's stream. The later completion cannot
	 * settle before the earlier one, so it stands for both. A backend whose completions carry more
	 * than their ordinal (Metal's prior-buffer value) builds on this rather than replacing it.
	 *
	 * @param other another completion
	 * @return the later of the two, or {@code null} if they cannot merge
	 */
	default StreamCompletion later(Semaphore other) {
		StreamCompletion theirs = getStream().own(other);
		if (theirs == null) return null;

		return theirs.follows(this) ? theirs : this;
	}

	/**
	 * Records in the stream's {@link FailureRecord} that this completion's submission did not
	 * execute, so that it and every later submission to the stream report the failure.
	 *
	 * @param cause why the submission did not execute
	 */
	default void recordFailure(Throwable cause) {
		getStream().getFailures().record(getOrdinal(), cause);
	}

	/**
	 * Returns the failure a wait for this completion must report because the stream recorded a
	 * failure at or before its submission, or {@code null} if none is recorded.
	 *
	 * @return the recorded failure covering this completion, or {@code null}
	 */
	default HardwareException getRecordedFailure() {
		return getStream().getFailures().failureAt(getOrdinal());
	}
}
