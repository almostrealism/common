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

/**
 * HTTP-based event delivery for the AR utils-http module.
 *
 * <p>This package contains an implementation of event delivery that serializes
 * {@code AbstractEvent} subclasses to JSON and POSTs them to a configured endpoint
 * using the Java HTTP client:</p>
 *
 * <ul>
 *   <li>{@link org.almostrealism.event.DefaultHttpEventDelivery} — concrete
 *       {@code EventDelivery} implementation that serializes events with Jackson
 *       and delivers them via HTTP POST.</li>
 * </ul>
 */
package org.almostrealism.event;
