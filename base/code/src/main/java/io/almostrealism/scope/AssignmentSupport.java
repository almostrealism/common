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

package io.almostrealism.scope;

/**
 * A part of generated code that can answer whether it assigns a value to an element of an
 * {@link ArrayVariable} within a range of positions.
 *
 * <p>The answer is conservative: {@code false} means no element of the range can be written by
 * the code this object generates, while {@code true} means some element of the range may be
 * written. It is read off the code itself rather than declared alongside it, so it cannot
 * contradict what the code does.</p>
 *
 * <p>Implemented by {@link Scope} (the assignments of the scope), by
 * {@link io.almostrealism.code.ExpressionAssignment} (the assignment's destination) and, through
 * {@link io.almostrealism.expression.ExpressionReferences}, by every
 * {@link io.almostrealism.expression.Expression} (what an assignment to the expression, as a
 * destination, writes).</p>
 */
public interface AssignmentSupport {
	/**
	 * Returns whether this code contains an assignment to an element of {@code array} at a
	 * position in {@code [start, end)}. Positions are in the frame of the storage
	 * {@code array} {@link Variable#getRootDelegate() ultimately delegates to}, so a view of
	 * {@code array} and {@code array} itself are the same array.
	 *
	 * @param array the array whose elements are in question
	 * @param start the first position in question
	 * @param end   the position after the last one in question
	 * @return false only when nothing this code generates can assign an element of
	 *         {@code array} in the range
	 */
	boolean containsAssignment(ArrayVariable<?> array, long start, long end);
}
