/*
 * Copyright 2020 Michael Murray
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

package io.flowtree.node;

/**
 * Formats node-related time durations as human-readable strings.
 *
 * <p>Previously hosted as a static method on {@link Node}.  Extracted here
 * so that {@code Node} stays within the 1500-line file-length limit while
 * keeping the formatting logic in one place.</p>
 */
class NodeTimeFormatter {

	/** Utility class — do not instantiate. */
	private NodeTimeFormatter() { }

	/**
	 * Formats a duration in milliseconds as a human-readable string of the form
	 * {@code "M minutes and S.SSS seconds (N)"}, omitting the minutes component
	 * when the duration is less than one minute.
	 *
	 * @param msec the duration to format, in milliseconds
	 * @return the formatted time string
	 */
	static String format(double msec) {
		int min = (int) Math.floor(msec / 60000);
		double sec = Math.floor(msec % 60000);
		sec = sec / 1000.0;

		StringBuilder b = new StringBuilder();

		if (min > 0) {
			b.append(min);
			b.append(" minutes and ");
		}

		b.append(sec);
		b.append(" seconds (");
		b.append(msec);
		b.append(")");

		return b.toString();
	}
}
