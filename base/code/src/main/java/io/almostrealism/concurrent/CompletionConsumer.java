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

import java.util.function.Consumer;

/**
 * A {@link Consumer} that can additionally receive the {@link Semaphore} for the completion
 * of the work that produced the value it accepts.
 *
 * <p>A producer that dispatches work asynchronously (a kernel whose result buffer is known as
 * soon as the dispatch is issued, but whose contents arrive only when the device completes)
 * can deliver {@code (value, completion)} to a {@link CompletionConsumer} without blocking the
 * host for the completion first. The consumer takes responsibility for ordering: it must not
 * read the value's contents until {@code completion} has fired, but it may chain the
 * {@link Semaphore} into downstream work (for example, merging it into a dependent dispatch's
 * {@code dependsOn} via {@link Semaphore#all(io.almostrealism.profile.OperationMetadata, java.util.List)})
 * so the ordering is enforced by the device rather than by a host wait.</p>
 *
 * <p>Delivery through the plain {@link Consumer#accept(Object)} contract implies the value is
 * already complete: the default implementation forwards to {@link #accept(Object, Semaphore)}
 * with a {@code null} completion, which every consumer must treat as "no outstanding work".</p>
 *
 * @param <T> the type of value consumed
 *
 * @author  Michael Murray
 */
@FunctionalInterface
public interface CompletionConsumer<T> extends Consumer<T> {
	/**
	 * Accepts a value along with the {@link Semaphore} that fires when the work producing
	 * the value's contents has completed.
	 *
	 * @param value      the produced value (its contents are valid only after {@code completion})
	 * @param completion the completion of the work that produced the value, or {@code null}
	 *                   when the value is already complete
	 */
	void accept(T value, Semaphore completion);

	@Override
	default void accept(T value) { accept(value, null); }
}
