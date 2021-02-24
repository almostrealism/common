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

package io.almostrealism.relation;

public interface Compactable {

	/**
	 * If this {@link Compactable} depends on other data (for example,
	 * other {@link Compactable}s) that can be combined with this one to
	 * reduce the complexity of the calculation and/or simplify the
	 * chain of data production if it were to be presented to a user
	 * for review/interpretation then this method should perform that
	 * combination. The intention is for this operation to be recursive,
	 * meaning that if a similar operation is available on the
	 * dependencies, that should be performed as well.
	 */
	void compact();

	/**
	 * This method should return true if the meaning of this {@link Compactable}
	 * does not depend on anything that can vary. This can be useful knowledge for
	 * optimizations. The default implementation returns false.
	 */
	default boolean isStatic() { return false; }
}
