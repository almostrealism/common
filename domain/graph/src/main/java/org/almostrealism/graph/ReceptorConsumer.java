/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.graph;

import io.almostrealism.relation.Producer;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link Receptor} that delivers received values to a Java {@link Consumer}.
 *
 * <p>{@code ReceptorConsumer} bridges the push-based cell model to standard Java
 * consumer callbacks. When data is pushed to this receptor, it evaluates the
 * producer and passes the result to the configured consumer function.</p>
 *
 * @param <T> the type of data received and delivered to the consumer
 * @see Receptor
 * @see CSVReceptor
 * @author Michael Murray
 */
public class ReceptorConsumer<T> implements Receptor<T> {
	/** The consumer function that receives evaluated values from push operations. */
	private Consumer<T> consumer;

	/**
	 * Creates a new receptor that delivers values to the given consumer.
	 *
	 * @param c the consumer to receive pushed values
	 */
	public ReceptorConsumer(Consumer<T> c) {
		this.consumer = c;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Evaluates the incoming producer and passes the result to the consumer.</p>
	 *
	 * @param protein the data producer to evaluate and deliver
	 * @return a supplier that performs the evaluation and delivery
	 */
	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		return () -> () -> consumer.accept(protein.get().evaluate());
	}

	/**
	 * Replaces the consumer function for subsequent push operations.
	 *
	 * @param c the new consumer to receive pushed values
	 */
	protected void setConsumer(Consumer<T> c) { this.consumer = c; }
}
