/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.io;

/**
 * The call that led into a class which is reporting something about it: the stack captured
 * when the origin is created, read from the first frame outside the reporting class.
 *
 * <p>A report that names only what happened is enough to know a call is worth replacing and
 * not enough to find it. A {@code CallOrigin} names the {@link #getOrigin() calling frame},
 * which belongs in the message itself (repeated warnings are suppressed by message, so call
 * sites reported without it would collapse into one), and the {@link #getCallers() calls above
 * it}, carried as lines of the report rather than as an attached exception, which is printed to
 * the error stream and never reaches the console.</p>
 *
 * <pre>{@code
 * CallOrigin origin = new CallOrigin(PackedCollection.class, 12);
 * warn(message + " (from " + origin.getOrigin() + ")" + origin.getCallers());
 * }</pre>
 *
 * @author Michael Murray
 */
public class CallOrigin {
	/** The class making the report, whose own frames are not part of the origin. */
	private final Class<?> reporter;

	/** The number of frames above the origin to include in {@link #getCallers()}. */
	private final int frames;

	/** The stack captured when this origin was created. */
	private final StackTraceElement[] stack;

	/**
	 * Captures the current stack as the origin of a report made by the given class.
	 *
	 * @param reporter the class making the report
	 * @param frames   the number of frames above the origin to include in {@link #getCallers()}
	 */
	public CallOrigin(Class<?> reporter, int frames) {
		this.reporter = reporter;
		this.frames = frames;
		this.stack = new Exception().getStackTrace();
	}

	/**
	 * Returns whether the given frame belongs to the reporting class (or to this class, which
	 * captured the stack), and so is not part of the origin.
	 *
	 * @param frame the frame
	 * @return {@code true} if the frame is the reporter's own
	 */
	private boolean isReporting(StackTraceElement frame) {
		return reporter.getName().equals(frame.getClassName())
				|| CallOrigin.class.getName().equals(frame.getClassName());
	}

	/**
	 * Returns the first frame outside the reporting class, which is the call being reported.
	 *
	 * @return a description of the calling frame, or "unknown origin"
	 */
	public String getOrigin() {
		for (StackTraceElement frame : stack) {
			if (!isReporting(frame)) {
				return frame.toString();
			}
		}

		return "unknown origin";
	}

	/**
	 * Returns the calls that led to the origin, one per line, each line beginning with a
	 * newline so that they can be appended to the report. The origin itself is omitted, since
	 * the report has already named it: repeating it would put the same frame on two
	 * consecutive lines of every report.
	 *
	 * @return the frames above the origin, at most the number requested
	 */
	public String getCallers() {
		StringBuilder callers = new StringBuilder();
		boolean atOrigin = true;
		int shown = 0;

		for (StackTraceElement frame : stack) {
			if (isReporting(frame)) continue;

			if (atOrigin) {
				atOrigin = false;
				continue;
			}

			if (shown++ >= frames) break;

			callers.append("\n    at ").append(frame);
		}

		return callers.toString();
	}
}
