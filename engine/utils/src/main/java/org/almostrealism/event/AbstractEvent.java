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

/**
 * Base class for all events in the event delivery system.
 * <p>
 * An event carries a human-readable name that identifies what occurred.
 * Subclasses add domain-specific fields such as duration, error details,
 * or tags.
 * </p>
 */
public abstract class AbstractEvent {
	/** Human-readable name identifying the type of event. */
	private String name;

	/** Constructs an unnamed event. */
	public AbstractEvent() {
		this(null);
	}

	/**
	 * Constructs an event with the specified name.
	 *
	 * @param name  the name of this event, or {@code null}
	 */
	public AbstractEvent(String name) {
		this.name = name;
	}

	/**
	 * Returns the name of this event.
	 *
	 * @return the event name, or {@code null} if not set
	 */
	public String getName() {
		return name;
	}

	/**
	 * Sets the name of this event.
	 *
	 * @param name  the event name
	 */
	public void setName(String name) {
		this.name = name;
	}
}
