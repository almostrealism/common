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

/**
 * An executable operation that can be submitted for asynchronous execution, chaining on a
 * prior operation's completion and yielding its own completion {@link Semaphore} without
 * (necessarily) blocking the host.
 *
 * <p>This is the operation side of the async-execution contract &mdash; the analog, for
 * operations that produce no value, of {@code io.almostrealism.streams.StreamingEvaluable}
 * for {@code Evaluable}. It belongs on the <em>executable</em>, so that any supplier of one
 * (any {@code ParallelProcess} whose compiled result implements it) can participate; it is
 * deliberately not tied to any single composite implementation.</p>
 *
 * <p>It lets a composite thread each member's completion into the next member's
 * {@code dependsOn} and issue a single completion wait at the end, moving the per-operation
 * wait down into the underlying compute provider. A provider that supports asynchronous
 * dispatch returns a live {@link Semaphore} backed by its native completion primitive (an
 * OpenCL {@code cl_event}, a Metal {@code MTLSharedEvent}, ...); a provider that does not
 * returns an already-completed semaphore, so chaining degrades transparently to sequential
 * synchronous execution.</p>
 */
public interface Submittable {

	/**
	 * Submits this operation for execution, optionally chaining on a prior operation's
	 * completion, and returns this operation's completion {@link Semaphore}.
	 *
	 * @param dependsOn the prior operation's completion that the provider should wait on
	 *                  (inside the provider) before this operation runs, or {@code null} to
	 *                  begin a chain
	 * @return this operation's completion semaphore (possibly already complete), suitable as
	 *         the next operation's {@code dependsOn}; may be {@code null} if the provider
	 *         publishes no completion handle (fully synchronous execution)
	 */
	Semaphore submit(Semaphore dependsOn);
}
