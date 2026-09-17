/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism.streams;

import io.almostrealism.relation.Evaluable;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * An adapter that wraps a synchronous {@link Evaluable} to provide a
 * {@link StreamingEvaluable} interface for asynchronous computation.
 *
 * <p>This adapter bridges the gap between the synchronous, pull-based model of
 * {@link Evaluable} and the asynchronous, push-based model of {@link StreamingEvaluable}.
 * When a request is made, the adapter submits the evaluation task to an {@link Executor}
 * and delivers the result to the downstream consumer when complete.</p>
 *
 * <p>This is the primary mechanism for converting synchronous evaluables to streaming
 * evaluables, and is used internally by {@link Evaluable#async()}:</p>
 * <pre>{@code
 * Evaluable<Result> sync = ...;
 * StreamingEvaluable<Result> async = new EvaluableStreamingAdapter<>(sync, executor);
 * // Or more simply:
 * StreamingEvaluable<Result> async = sync.async(executor);
 * }</pre>
 *
 * <p>The default constructor uses a synchronous executor ({@code Runnable::run}) which
 * executes immediately on the calling thread. For true asynchronous behavior, provide
 * a custom executor such as a thread pool or dedicated async executor.</p>
 *
 * @param <T> the type of result produced by the computation
 *
 * @see StreamingEvaluable
 * @see StreamingEvaluableBase
 * @see Evaluable#async()
 * @see Evaluable#async(Executor)
 *
 * @author  Michael Murray
 */
public class EvaluableStreamingAdapter<T> extends StreamingEvaluableBase<T> {
	/** The synchronous evaluable whose result is forwarded to the downstream consumer. */
	private final Evaluable<T> evaluable;
	/** The executor used to run the evaluation, enabling asynchronous delivery. */
	private final Executor executor;

	/**
	 * Creates a new adapter for the specified evaluable using a synchronous executor.
	 *
	 * <p>The synchronous executor ({@code Runnable::run}) executes the computation
	 * immediately on the calling thread. This is useful for testing or when
	 * asynchronous behavior is not required.</p>
	 *
	 * @param evaluable the evaluable to wrap; must not be null
	 */
	public EvaluableStreamingAdapter(Evaluable<T> evaluable) {
		this(evaluable, Runnable::run);
	}

	/**
	 * Creates a new adapter for the specified evaluable using the given executor.
	 *
	 * <p>The executor determines when and on which thread the computation will run.
	 * Common choices include:</p>
	 * <ul>
	 *   <li>{@code Runnable::run} - Synchronous execution on calling thread</li>
	 *   <li>{@code r -> new Thread(r).start()} - New thread per request</li>
	 *   <li>{@code Executors.newFixedThreadPool(n)} - Thread pool execution</li>
	 *   <li>Custom executors for specific scheduling requirements</li>
	 * </ul>
	 *
	 * @param evaluable the evaluable to wrap; must not be null
	 * @param executor the executor to use for computation; must not be null
	 */
	public EvaluableStreamingAdapter(Evaluable<T> evaluable, Executor executor) {
		this.evaluable = evaluable;
		this.executor = executor;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation submits the evaluation to the configured executor.
	 * When the evaluation completes, the result is passed to the downstream consumer.
	 * The method returns immediately without waiting for the computation to complete
	 * (unless a synchronous executor is used).</p>
	 *
	 * <p>The adapter wraps a synchronous {@link Evaluable} and performs no hardware
	 * dispatch of its own, so there is no provider into which {@code dependsOn} could be
	 * chained. Unlike a hardware dispatch, which only chains a device handle, the wrapped
	 * evaluable reads the actual contents of {@code args} on the thread that calls it, so
	 * the dependency is still honored: submission to the executor is non-blocking, but the
	 * submitted task waits for {@code dependsOn} before evaluating, exactly as
	 * {@link #request(Object[], Semaphore, Consumer)} does.</p>
	 *
	 * @param args      the arguments to pass to the underlying evaluable
	 * @param dependsOn completion this evaluation must be ordered after, or
	 *                  {@code null} when there is no dependency
	 */
	@Override
	public void request(Object[] args, Semaphore dependsOn) {
		request(args, dependsOn, getDownstream());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delivers to {@code downstream} directly instead of {@link #getDownstream()}, so that
	 * this adapter can serve several independent requesters (a kernel wrapper re-viewing this
	 * adapter's result through a {@code resultProcessor}, requested repeatedly across a
	 * streaming pipeline's lifetime, for example) without any of them contending for
	 * {@link #setDownstream}.</p>
	 *
	 * <p>Submission to the executor is non-blocking, but the submitted task waits for
	 * {@code dependsOn} (when non-null) before calling {@link Evaluable#evaluate(Object...)
	 * evaluate}. The wrapped evaluable is a host function that reads the contents of
	 * {@code args} rather than chaining a device handle, so a dispatch it depends on must
	 * have completed before those contents are read; otherwise the evaluation could observe
	 * memory the dependency has not finished writing.</p>
	 *
	 * @param args       the arguments to pass to the underlying evaluable
	 * @param dependsOn  completion this evaluation must be ordered after, or
	 *                   {@code null} when there is no dependency
	 * @param downstream the consumer to receive the result of this request
	 */
	@Override
	public void request(Object[] args, Semaphore dependsOn, Consumer<T> downstream) {
		executor.execute(() -> {
			if (dependsOn != null) dependsOn.waitFor();
			downstream.accept(evaluable.evaluate(args));
		});
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Always {@code true}: as documented on {@link #request(Object[], Semaphore, Consumer)},
	 * the submitted task waits for a non-null {@code dependsOn} before reading {@code args}, so
	 * this adapter orders its work after a supplied dependency instead of disregarding it.</p>
	 */
	@Override
	public boolean isDispatchBacked() {
		return true;
	}
}
