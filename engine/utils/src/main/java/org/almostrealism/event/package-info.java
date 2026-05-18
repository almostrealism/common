/*
 * Copyright 2024 Michael Murray
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

/**
 * Lightweight event model and asynchronous delivery infrastructure.
 *
 * <p>This package provides a simple, generic event system used to record
 * and transmit structured events (such as job completions or error reports)
 * to external consumers.</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link org.almostrealism.event.AbstractEvent} - Base class for all events,
 *       carrying a name identifier</li>
 *   <li>{@link org.almostrealism.event.DefaultEvent} - Standard event with a
 *       timestamp, duration, and string-valued tags</li>
 *   <li>{@link org.almostrealism.event.DefaultErrorEvent} - Event representing
 *       an exception, including type, message, and stack trace</li>
 *   <li>{@link org.almostrealism.event.EventDelivery} - Interface for delivering
 *       events to a consumer, one at a time</li>
 *   <li>{@link org.almostrealism.event.EventDeliveryQueue} - Thread-safe queue
 *       that periodically delivers batches of events via a scheduled executor</li>
 *   <li>{@link org.almostrealism.event.SimpleEventServer} - Minimal HTTP server
 *       that accepts POST requests and logs the received payload</li>
 * </ul>
 */
package org.almostrealism.event;
