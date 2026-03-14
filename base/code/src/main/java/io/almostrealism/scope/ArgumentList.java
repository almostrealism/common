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

package io.almostrealism.scope;

import io.almostrealism.relation.Parent;

/**
 * Represents a collection of {@link Argument}s that can be passed to a {@link Scope} for execution.
 * <p>This interface extends {@link Parent} to provide a hierarchical view of arguments,
 * allowing them to be treated as a tree structure where each argument can have dependencies.</p>
 *
 * <p>{@link ArgumentList} is used during scope compilation and execution to manage the
 * input parameters that a computation requires. Implementations track which arguments
 * are needed and can provide count information for runtime allocation.</p>
 *
 * <h2>Usage</h2>
 * <p>Classes implementing this interface provide access to the arguments needed for
 * a specific computation context. The argument list may be dynamic, with arguments
 * added during scope analysis.</p>
 *
 * @param <T> the type of value held by the arguments in this list
 *
 * @see Argument
 * @see Scope
 * @see Parent
 */
public interface ArgumentList<T> extends Parent<Argument<? extends T>> {

	/**
	 * Returns the number of arguments in this list.
	 * <p>The default implementation throws {@link UnsupportedOperationException}.
	 * Implementations should override this method to provide the actual count.</p>
	 *
	 * @return the number of arguments
	 * @throws UnsupportedOperationException if the count is not available
	 */
	default int getArgsCount() {
		throw new UnsupportedOperationException();
	}
}
