/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism.streams;

import java.util.function.Consumer;

/**
 * Abstract base class for {@link StreamingEvaluable} implementations that provides
 * standard downstream consumer management.
 *
 * <p>This class manages the downstream consumer reference and provides protected access
 * to it for subclasses. It enforces single-assignment semantics for the downstream
 * consumer - once set, attempting to change it to a different consumer will throw
 * an {@link UnsupportedOperationException}.</p>
 *
 * <p>Subclasses should call {@link #getDownstream()} to retrieve the consumer
 * and deliver computation results:</p>
 * <pre>{@code
 * public class MyStreamingEvaluable extends StreamingEvaluableBase<Result> {
 *     @Override
 *     public void request(Object[] args) {
 *         Result result = performComputation(args);
 *         getDownstream().accept(result);
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type of result produced by the computation
 *
 * @see StreamingEvaluable
 * @see EvaluableStreamingAdapter
 *
 * @author  Michael Murray
 */
public abstract class StreamingEvaluableBase<T> implements StreamingEvaluable<T> {
	private Consumer<T> downstream;

	/**
	 * Returns the downstream consumer to which computation results should be delivered.
	 *
	 * <p>Subclasses should call this method when computation completes to push the
	 * result to the consumer. May return {@code null} if no downstream has been set.</p>
	 *
	 * @return the downstream consumer, or {@code null} if none has been set
	 */
	protected Consumer<T> getDownstream() { return downstream; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation enforces single-assignment semantics: once a downstream
	 * consumer is set, it cannot be changed to a different consumer. Attempting to
	 * do so will throw an {@link UnsupportedOperationException}. However, setting
	 * the same consumer again (idempotent call) is permitted.</p>
	 *
	 * @param consumer the consumer to receive computation results; must not be null
	 * @throws UnsupportedOperationException if a different consumer has already been set
	 */
	@Override
	public void setDownstream(Consumer<T> consumer) {
		if (downstream != null && downstream != consumer) {
			throw new UnsupportedOperationException();
		}

		this.downstream = consumer;
	}
}
