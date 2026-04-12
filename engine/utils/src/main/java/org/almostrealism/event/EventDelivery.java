/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.event;

import java.util.function.Supplier;

/**
 * Interface for delivering events to a consumer one at a time.
 *
 * <p>The {@link #deliver(Object)} method is called for each event.
 * The {@link #deliverAll(Supplier)} default method repeatedly pulls events
 * from the supplier until {@link #deliver(Object)} returns {@code false}
 * (indicating no more events are available).</p>
 *
 * @param <T>  the type of event
 */
public interface EventDelivery<T> {
	/**
	 * Delivers all events provided by the supplier until the supplier returns
	 * a value for which {@link #deliver(Object)} returns {@code false}.
	 *
	 * @param events  a supplier that provides events one at a time; returning
	 *                a value that causes {@link #deliver} to return {@code false}
	 *                (typically {@code null}) signals the end of the sequence
	 * @return        the total number of events successfully delivered
	 */
	default int deliverAll(Supplier<T> events) {
		int total = 0;
		for (; deliver(events.get()); total++);
		return total;
	}

	/**
	 * Delivers a single event to the consumer.
	 *
	 * @param event  the event to deliver
	 * @return       {@code true} if the event was delivered successfully,
	 *               {@code false} if delivery should stop (e.g., {@code null} event)
	 */
	boolean deliver(T event);
}
