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

package org.almostrealism.io;

/**
 * Interface for objects that can provide a human-readable description of themselves.
 *
 * <p>This interface is used throughout the Almost Realism framework to provide
 * more informative string representations than {@code toString()}, particularly
 * for complex objects like computation graphs, models, and operations.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyOperation implements Describable {
 *     @Override
 *     public String describe() {
 *         return "MyOperation[input=" + input + ", output=" + output + "]";
 *     }
 * }
 *
 * // Get description safely
 * String desc = Describable.describe(myObject);  // Works even if myObject is null
 * }</pre>
 *
 * @see Object#toString()
 */
public interface Describable {
	/**
	 * Returns a human-readable description of this object.
	 *
	 * @return a descriptive string
	 */
	String describe();

	/**
	 * Returns a description of the given object, using {@link #describe()} if available,
	 * otherwise {@link Object#toString()}.
	 *
	 * @param o the object to describe, or null
	 * @return the description, or null if the object is null
	 */
	static String describe(Object o) {
		if (o == null)
			return null;

		return o instanceof Describable ? ((Describable) o).describe() : o.toString();
	}
}
