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

public class EventDeliveryQueue<T extends AbstractEvent> implements ConsoleFeatures {
	private final ConcurrentLinkedQueue<T> events;
	private final ScheduledExecutorService executor;
	private final EventDelivery<T> delivery;

	public EventDeliveryQueue(EventDelivery<T> delivery) {
		this.events = new ConcurrentLinkedQueue<>();
		this.executor = Executors.newScheduledThreadPool(1);
		this.delivery = Objects.requireNonNull(delivery);
	}

	public void start() {
		executor.scheduleAtFixedRate(this::deliverAll, 10,
				10, java.util.concurrent.TimeUnit.SECONDS);
	}

	public void addEvent(T e) {
		events.add(e);
	}

	protected void deliverAll() {
		delivery.deliverAll(events::poll);
	}
}
