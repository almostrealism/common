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

import io.almostrealism.relation.Computable;

import java.util.function.Consumer;

/**
 * A {@link StreamingEvaluable} works in a similar manner to an
 * {@link io.almostrealism.relation.Evaluable}, but performs any
 * necessary computation asynchronously.
 *
 * <p>While {@link io.almostrealism.relation.Evaluable} performs synchronous computation
 * and blocks until results are available, {@link StreamingEvaluable} uses a push-based
 * model where results are delivered to a downstream {@link Consumer} when they become
 * available. This enables non-blocking computation patterns and better integration
 * with reactive or event-driven architectures.</p>
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * StreamingEvaluable<Result> streaming = evaluable.async();
 * streaming.setDownstream(result -> processResult(result));
 * streaming.request(args);  // Returns immediately; result delivered later
 * }</pre>
 *
 * @param <T> the type of result produced by the computation
 *
 * @see io.almostrealism.relation.Evaluable
 * @see StreamingEvaluableBase
 * @see EvaluableStreamingAdapter
 *
 * @author  Michael Murray
 */
public interface StreamingEvaluable<T> extends Computable {
	/**
	 * Initiates an asynchronous computation request with no arguments.
	 * The result will be delivered to the downstream consumer when available.
	 *
	 * <p>This is a convenience method equivalent to calling {@code request(new Object[0])}.</p>
	 *
	 * @see #request(Object[])
	 * @see #setDownstream(Consumer)
	 */
	default void request() {
		request(new Object[0]);
	}

	/**
	 * Initiates an asynchronous computation request with the specified arguments.
	 * The result will be delivered to the downstream consumer when available.
	 *
	 * <p>This method returns immediately without waiting for the computation to complete.
	 * The actual computation is performed asynchronously, and the result is pushed to
	 * the downstream consumer set via {@link #setDownstream(Consumer)}.</p>
	 *
	 * @param args the arguments required for computation, in the same format as
	 *             would be passed to {@link io.almostrealism.relation.Evaluable#evaluate(Object...)}
	 *
	 * @see #setDownstream(Consumer)
	 */
	void request(Object args[]);

	/**
	 * Sets the downstream consumer that will receive computation results.
	 *
	 * <p>The consumer will be invoked asynchronously each time a computation
	 * completes successfully. The thread on which the consumer is invoked depends
	 * on the implementation.</p>
	 *
	 * @param consumer the consumer to receive computation results; must not be null
	 *
	 * @see #request(Object[])
	 */
	void setDownstream(Consumer<T> consumer);
}
