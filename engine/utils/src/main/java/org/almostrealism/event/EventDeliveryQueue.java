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

import org.almostrealism.io.ConsoleFeatures;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe queue that buffers events and delivers them in periodic batches.
 * <p>
 * Events are added via {@link #addEvent(AbstractEvent)} from any thread. A
 * single-threaded scheduled executor polls the queue every 10 seconds and
 * passes all pending events to the configured {@link EventDelivery}.
 * </p>
 *
 * @param <T>  the type of event, must extend {@link AbstractEvent}
 */
public class EventDeliveryQueue<T extends AbstractEvent> implements ConsoleFeatures {
	/** The thread-safe queue holding events waiting to be delivered. */
	private final ConcurrentLinkedQueue<T> events;

	/** The scheduled executor that periodically triggers batch delivery. */
	private final ScheduledExecutorService executor;

	/** The delivery target that receives events from the queue. */
	private final EventDelivery<T> delivery;

	/**
	 * Creates a new delivery queue backed by the given {@link EventDelivery}.
	 *
	 * @param delivery  the delivery target; must not be {@code null}
	 */
	public EventDeliveryQueue(EventDelivery<T> delivery) {
		this.events = new ConcurrentLinkedQueue<>();
		this.executor = Executors.newScheduledThreadPool(1);
		this.delivery = Objects.requireNonNull(delivery);
	}

	/**
	 * Starts the background scheduler that delivers events every 10 seconds.
	 */
	public void start() {
		executor.scheduleAtFixedRate(this::deliverAll, 10,
				10, TimeUnit.SECONDS);
	}

	/**
	 * Adds an event to the queue for delivery in the next scheduled batch.
	 *
	 * @param e  the event to enqueue
	 */
	public void addEvent(T e) {
		events.add(e);
	}

	/**
	 * Delivers all currently queued events to the {@link EventDelivery}.
	 * This method is called periodically by the scheduler and may also be
	 * called directly.
	 */
	protected void deliverAll() {
		delivery.deliverAll(events::poll);
	}
}
