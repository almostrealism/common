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

import io.almostrealism.streams.StreamingEvaluable;

/**
 * A {@link StreamingEvaluable} whose computation can be ordered after the
 * completion of prior work, expressed as a {@link Semaphore}.
 *
 * <p>This is the streaming counterpart of {@link Submittable#submit(Semaphore)}:
 * where a plain {@link StreamingEvaluable#request(Object[])} begins its work
 * immediately, {@link #request(Object[], Semaphore)} orders the computation's
 * dispatch after the given completion by chaining it through the provider —
 * without blocking the requesting thread, since a request (like a submit) must
 * return while its dependency may still be outstanding.</p>
 *
 * <p>This ordering matters most for argument preparation: when an operation is
 * dispatched with a {@code dependsOn} completion, an argument whose evaluation is
 * itself a dispatch that reads memory written by that prior work must not execute
 * until it has finished. Delivery remains asynchronous — results may still arrive
 * with their own completion via {@link CompletionConsumer}.</p>
 *
 * @param <T> the type of result produced by the computation
 *
 * @see StreamingEvaluable
 * @see Submittable
 * @see CompletionConsumer
 */
public interface DependentStreamingEvaluable<T> extends StreamingEvaluable<T> {
	/**
	 * Initiates an asynchronous computation request whose dispatch is ordered
	 * after the given completion, without blocking the requesting thread. The
	 * result will be delivered to the downstream consumer when available.
	 *
	 * @param args      the arguments required for computation, in the same format
	 *                  as {@link StreamingEvaluable#request(Object[])}
	 * @param dependsOn completion the dispatch must chain on, or {@code null}
	 *                  when there is no dependency
	 */
	void request(Object[] args, Semaphore dependsOn);
}
