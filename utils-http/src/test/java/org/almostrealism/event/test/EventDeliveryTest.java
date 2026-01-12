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

package org.almostrealism.event.test;

import org.almostrealism.event.DefaultEvent;
import org.almostrealism.event.EventDeliveryQueue;
import org.almostrealism.event.SimpleEventServer;
import org.almostrealism.event.DefaultHttpEventDelivery;
import org.junit.Test;

import java.io.IOException;

public class EventDeliveryTest {
	@Test
	public void deliver() throws IOException, InterruptedException {
		SimpleEventServer server = new SimpleEventServer();
		server.start();

		EventDeliveryQueue<DefaultEvent> delivery = new EventDeliveryQueue<>(
				new DefaultHttpEventDelivery<>("http://localhost:8080/test"));
		delivery.start();

		delivery.addEvent(new DefaultEvent("test1", 1000));
		delivery.addEvent(new DefaultEvent("test2", 2000));

		Thread.sleep(32000);

		delivery.addEvent(new DefaultEvent("test3", 4000));
		delivery.addEvent(new DefaultEvent("test4", 3000));

		Thread.sleep(25000);
	}
}
