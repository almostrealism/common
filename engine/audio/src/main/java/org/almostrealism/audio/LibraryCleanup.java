/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.audio;

/**
 * One category of leftover library data a user may choose to delete: data an
 * application no longer needs but keeps until the user removes it deliberately
 * (for example, files left behind by an old library format, or analysis data
 * kept only so an earlier application version keeps working).
 *
 * <p>Each implementation is the report of that data. It describes the data to
 * the user, reports the space it occupies, and performs the deletion, all from
 * its own state, so an application can offer any mix of cleanups through a
 * single prompt without knowing their kinds.</p>
 *
 * @see WaveFeatureCleanup
 */
public interface LibraryCleanup {
	/**
	 * Returns whether this cleanup has anything to remove and should therefore
	 * be offered to the user. By default, data is present when it occupies any
	 * space; implementations whose data occupies no disk space must override.
	 *
	 * @return true if there is data to remove
	 */
	default boolean isPresent() {
		return totalBytes() > 0;
	}

	/**
	 * Returns a human-readable explanation of what the data is and why it is
	 * safe to remove, suitable for showing to a user.
	 *
	 * @return the description of the data
	 */
	String description();

	/**
	 * Returns the space the data occupies, in bytes. May be zero for data that
	 * occupies no disk space of its own.
	 *
	 * @return the size in bytes
	 */
	long totalBytes();

	/**
	 * Deletes the data this cleanup covers.
	 *
	 * @return the number of items removed
	 */
	int remove();
}
