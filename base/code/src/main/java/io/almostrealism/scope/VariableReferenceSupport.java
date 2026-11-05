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
 * A part of generated code that can answer whether it assigns a value to, or reads the value
 * of, an element of an {@link ArrayVariable} within a range of positions.
 *
 * <p>Both answers are conservative: {@code false} means no element of the range can be written
 * (or read) by the code this object generates, while {@code true} means some element of the
 * range may be. They are read off the code itself rather than declared alongside it, so they
 * cannot contradict what the code does.</p>
 *
 * <p>Implemented by {@link Scope} (the assignments of the scope), by
 * {@link io.almostrealism.code.ExpressionAssignment} (the assignment's destination, and what it
 * evaluates) and, through {@link io.almostrealism.expression.ExpressionReferences}, by every
 * {@link io.almostrealism.expression.Expression} (what an assignment to the expression, as a
 * destination, writes, and what evaluating the expression reads).</p>
 */
public interface VariableReferenceSupport {
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

	/**
	 * Returns whether this code reads the value of an element of {@code array} at a position in
	 * {@code [start, end)}: whether it references such an element anywhere other than as the
	 * element an assignment writes. Positions are in the same frame as for
	 * {@link #containsAssignment(ArrayVariable, long, long)}. An element that is both written and
	 * read (a read-modify-write) is read.
	 *
	 * @param array the array whose elements are in question
	 * @param start the first position in question
	 * @param end   the position after the last one in question
	 * @return false only when nothing this code generates can read an element of {@code array}
	 *         in the range
	 */
	default boolean containsRead(ArrayVariable<?> array, long start, long end) {
		return getReadPositions(array).contains(start, end);
	}

	/**
	 * Returns every position of {@code array} this code may read, gathered in one walk of the
	 * code (see {@link #appendReadPositions(ReadPositions)}), so that any number of ranges can then be
	 * asked about without walking it again.
	 *
	 * @param array the array whose reads are gathered
	 * @return the positions of {@code array} this code may read
	 */
	default ReadPositions getReadPositions(ArrayVariable<?> array) {
		ReadPositions reads = new ReadPositions(array);
		appendReadPositions(reads);
		return reads;
	}

	/**
	 * Appends to {@code reads}, which this method modifies, every read this code makes of
	 * {@link ReadPositions#isOf(Variable) its array}, on the terms of
	 * {@link #containsRead(ArrayVariable, long, long)}. A part of the code
	 * that contains other parts descends only into those {@code reads}
	 * {@link ReadPositions#visit(VariableReferenceSupport) has not visited}, so code that shares a subtree is walked
	 * once however often the subtree occurs.
	 *
	 * @param reads the record of the reads found so far
	 */
	void appendReadPositions(ReadPositions reads);
}
