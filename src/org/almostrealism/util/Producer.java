/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.util;

/**
 * The Producer interface is implemented by classes that represent a
 * repeatedly evaluated function.
 * 
 * @see org.almostrealism.util.Editable
 *
 * @author  Michael Murray
 */
// TODO  A subinterface of this interface should implement PathElement
//       and the compact method should be moved there
public interface Producer<T> {
	default T evaluate() {
		return evaluate(new Object[0]);
	}

	T evaluate(Object args[]);

	/**
	 * If this {@link Producer} depends on other data (for example,
	 * other {@link Producer}s) that can be combined with this one to
	 * reduce the complexity of the calculation and/or simplify the
	 * chain of data production if it were to be presented to a user
	 * for review/interpretation then this method should perform that
	 * combination. The intention is for this operation to be recursive,
	 * meaning that if a similar operation is available on the producer
	 * dependencies that should be performed as well.
	 */
	void compact(); // TODO  Perhaps this should return a number to indicate how much compaction was achieved (later comment: WTF does that mean?)

	/**
	 * This method should return true if the value of this {@link Producer}
	 * does not depend on any argument passed to the evaluate method. This
	 * can be useful knowledge for optimizations. The default implementation
	 * returns false.
	 */
	default boolean isStatic() { return false; }
}
