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

import java.util.Set;

/**
 * A part of generated code that can answer whether it assigns a value to, or reads the value
 * of, an element of an {@link ArrayVariable} within a range of positions.
 *
 * <p>Both answers are conservative: {@code false} means no element of the range can be written
 * (or read) by the code this object generates, while {@code true} means some element of the
 * range may be. They are read off the code itself rather than declared alongside it, so they
 * cannot contradict what the code does.</p>
 *
 * <p>Implemented by {@link Scope} (the statements and child scopes of the scope), by every
 * {@link io.almostrealism.code.Statement} (what the statement writes and reads, such as an
 * {@link io.almostrealism.code.ExpressionAssignment} through its destination and what it
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
	 * Appends to {@code names}, which this method modifies, the name of every variable this code
	 * may assign a value to: the name-based counterpart to
	 * {@link #containsAssignment(ArrayVariable, long, long)}, for analyses that track variables
	 * by name rather than by {@link ArrayVariable}, such as the loop-variance analysis of
	 * {@link Repeated}.
	 *
	 * <p>Only what the code names is added. Code that runs a body it does not contain (a call,
	 * a required scope, explicit code) may also change values it does not name; whether it may
	 * write an element of a particular array is answered by
	 * {@link #containsAssignment(ArrayVariable, long, long)}.</p>
	 *
	 * @param names the set to which the names are added
	 */
	void appendAssignedNames(Set<String> names);

	/**
	 * Appends to {@code arrays}, which this method modifies, the {@link ArrayVariable} whose
	 * storage this code may write, identified by the variable itself rather than by its name: the
	 * identity-keyed counterpart to {@link #appendAssignedNames(Set)}. An analysis that must
	 * decide whether a declaration reads memory the loop writes cannot do so by name, because an
	 * element read through an {@link io.almostrealism.expression.InstanceReference} contributes no
	 * name to {@link io.almostrealism.expression.ExpressionReferences#appendReferencedNames(Set)},
	 * and because argument aggregation lets one buffer be reached through variables of different
	 * names; comparing the written variable against a read with
	 * {@link io.almostrealism.expression.ExpressionReferences#containsReference(Variable)}, which
	 * matches on {@link Variable#hasSameRoot(Variable) shared storage}, answers both soundly.
	 *
	 * <p>This default adds nothing: an expression evaluated reads but does not write, and only the
	 * code that writes a known array ({@link io.almostrealism.code.ExpressionAssignment} through
	 * its destination, and {@link Scope} through its contents) contributes one. The name-based
	 * {@link #appendAssignedNames(Set)} remains the conservative account the variance analysis also
	 * consults for everything a precise array identity is not available for.</p>
	 *
	 * @param arrays the set to which the written arrays are added
	 */
	default void appendWrittenArrays(Set<Variable<?, ?>> arrays) { }

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
