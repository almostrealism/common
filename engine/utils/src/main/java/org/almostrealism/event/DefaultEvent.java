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

package org.almostrealism.event;

import java.util.HashMap;
import java.util.Map;

/**
 * A standard event with a timestamp, duration, and optional string-valued tags.
 * <p>
 * The timestamp is set to {@link System#currentTimeMillis()} at construction time.
 * Tags are lazily initialized and can hold arbitrary metadata as key-value pairs.
 * </p>
 */
public class DefaultEvent extends AbstractEvent {
	/** Wall-clock timestamp in milliseconds when the event was created. */
	private long time;

	/** Duration of the activity represented by this event, in milliseconds. */
	private long duration;

	/** Optional string-valued metadata tags, lazily initialized. */
	private Map<String, String> tags;

	/** Constructs an empty event with no name, time, or duration. */
	public DefaultEvent() { }

	/**
	 * Constructs an event with the specified name and duration, capturing the current time.
	 *
	 * @param name      the event name
	 * @param duration  the duration of the activity in milliseconds
	 */
	public DefaultEvent(String name, long duration) {
		super(name);
		setDuration(duration);
		setTime(System.currentTimeMillis());
	}

	/**
	 * Returns the tag map for this event, lazily initializing it if necessary.
	 *
	 * @return the mutable tag map
	 */
	public Map<String, String> getTags() {
		if (tags == null) tags = new HashMap<>();
		return tags;
	}

	/**
	 * Returns {@code true} if this event has at least one tag.
	 *
	 * @return whether the tag map is non-empty
	 */
	public boolean hasTags() {
		return tags != null && !tags.isEmpty();
	}

	/** @return  the wall-clock timestamp in milliseconds when this event was created */
	public long getTime() { return time; }

	/** @param time  the wall-clock timestamp in milliseconds */
	public void setTime(long time) { this.time = time; }

	/** @return  the duration of this event in milliseconds */
	public long getDuration() { return duration; }

	/** @param duration  the duration of this event in milliseconds */
	public void setDuration(long duration) { this.duration = duration; }
}
