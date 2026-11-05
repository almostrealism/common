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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The positions of one {@link ArrayVariable} that a body of generated code may read, gathered
 * in a single walk of that code (see {@link VariableReferenceSupport#collectReads}).
 *
 * <p>Each read is recorded as the range of positions it may reach, with either side left open
 * when it cannot be bounded, and a read that names no element records the whole array. Whether
 * any position in a range is read is then answered from the recorded ranges by
 * {@link #contains(long, long)}, without walking the code again, so asking about many ranges of
 * one array (every slice of an aggregate, for example) costs one walk rather than one per
 * range.</p>
 *
 * <p>The walk visits each distinct node once: generated expressions share subtrees, and what a
 * subtree reads does not depend on where it occurs, so a subtree already visited contributes
 * nothing new (see {@link #visit(Object)}).</p>
 */
public class ReadPositions {
	/** The array whose reads are recorded. */
	private final ArrayVariable<?> array;
	/** The nodes already visited by the walk, compared by identity. */
	private final Set<Object> visited;
	/** The lower bound of each recorded read, {@link Long#MIN_VALUE} when it is open. */
	private final List<Long> lower;
	/** The upper bound of each recorded read, {@link Long#MAX_VALUE} when it is open. */
	private final List<Long> upper;
	/** Whether a read of any position at all has been recorded. */
	private boolean everything;

	/**
	 * Creates an empty record of the reads of {@code array}.
	 *
	 * @param array the array whose reads are recorded
	 */
	public ReadPositions(ArrayVariable<?> array) {
		this.array = array;
		this.visited = Collections.newSetFromMap(new IdentityHashMap<>());
		this.lower = new ArrayList<>();
		this.upper = new ArrayList<>();
	}

	/** Returns the array whose reads are recorded. */
	public ArrayVariable<?> getArray() { return array; }

	/**
	 * Marks {@code node} as visited, returning whether it had not been visited before. A node
	 * that was already visited has already recorded everything it reads, so the walk need not
	 * descend into it again.
	 *
	 * @param node the node about to be walked
	 * @return true if the node has not been walked before and nothing has yet been found to read
	 *         every position, so walking it may record something new
	 */
	public boolean visit(Object node) {
		return !everything && visited.add(node);
	}

	/**
	 * Records a read of a position between {@code lowerBound} and {@code upperBound}, inclusive;
	 * a bound that is empty leaves that side of the range open.
	 *
	 * @param lowerBound the lowest position the read may reach, if known
	 * @param upperBound the highest position the read may reach, if known
	 */
	public void add(OptionalLong lowerBound, OptionalLong upperBound) {
		if (lowerBound.isEmpty() && upperBound.isEmpty()) {
			addEverything();
			return;
		}

		lower.add(lowerBound.orElse(Long.MIN_VALUE));
		upper.add(upperBound.orElse(Long.MAX_VALUE));
	}

	/** Records a read that may reach any position of the array. */
	public void addEverything() {
		everything = true;
	}

	/** Returns whether a read that may reach any position of the array has been recorded. */
	public boolean isEverything() { return everything; }

	/**
	 * Returns whether a recorded read may reach a position in {@code [start, end)}.
	 *
	 * @param start the first position in question
	 * @param end   the position after the last one in question
	 * @return true if some recorded read may reach a position in the range
	 */
	public boolean contains(long start, long end) {
		if (everything) return true;

		for (int i = 0; i < lower.size(); i++) {
			if (upper.get(i) >= start && lower.get(i) < end) return true;
		}

		return false;
	}
}
