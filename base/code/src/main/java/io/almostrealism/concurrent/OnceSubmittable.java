/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.concurrent;

import io.almostrealism.streams.Semaphore;

import java.util.Arrays;
import java.util.List;

/**
 * A {@link Submittable} group of operations whose work is done by its first submission only.
 *
 * <p>The first submission submits every operation of the group (see
 * {@link Submittable#submit(List, Semaphore)}). Every later submission issues nothing, and returns a
 * completion covering both its own dependency and the group's work for as long as that work may
 * still be outstanding, so whatever is chained on any submission, whichever thread makes it, is
 * ordered after the group. Once the group's work is known to have completed, a later submission
 * returns its dependency alone.</p>
 *
 * <p>This suits work whose result stays valid for as long as its owner lives, such as a copy of
 * memory that cannot change into a buffer that nothing else writes. Submissions are claimed under
 * this object's lock, so concurrent submissions issue the group exactly once.</p>
 */
public class OnceSubmittable implements Submittable {
	/** The operations submitted by the first submission. */
	private final List<Submittable> operations;
	/** True once {@link #operations} have been submitted. */
	private boolean submitted;
	/**
	 * The completion of {@link #operations} while it may still be outstanding; null before they are
	 * submitted, when their submission published no completion, and once it has completed.
	 */
	private Semaphore completion;

	/**
	 * Creates a group whose operations are submitted by its first submission only.
	 *
	 * @param operations the operations to submit, which do not depend on one another
	 */
	public OnceSubmittable(List<Submittable> operations) {
		this.operations = operations;
	}

	/**
	 * Submits the group if no submission has yet, ordered after {@code dependsOn}; otherwise issues
	 * nothing.
	 *
	 * @param dependsOn the completion the group, or the caller's chain, must be ordered after
	 * @return a completion covering {@code dependsOn} and the group's work while it may be outstanding
	 */
	@Override
	public synchronized Semaphore submit(Semaphore dependsOn) {
		if (submitted) {
			return completion == null ? dependsOn : Semaphore.all(Arrays.asList(dependsOn, completion));
		}

		Semaphore result = Submittable.submit(operations, dependsOn);
		submitted = true;
		completion = result;

		// Once the group is complete, later submissions need not depend on it at all
		if (result != null) result.whenComplete(() -> release(result));
		return result;
	}

	/**
	 * Stops ordering later submissions after the group, whose work {@code done} reports to have
	 * completed.
	 *
	 * @param done the completion of the group's submission
	 */
	private synchronized void release(Semaphore done) {
		if (completion == done) completion = null;
	}
}
