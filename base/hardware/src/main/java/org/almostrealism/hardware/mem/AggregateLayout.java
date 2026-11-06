/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.hardware.mem;

import java.util.BitSet;

/**
 * The aggregate argument of a compiled kernel, as the {@link MemoryDataArgumentMap} it was
 * compiled against {@link MemoryDataArgumentMap#limitCopiesTo(io.almostrealism.scope.Scope)
 * read it off} the compiled scope: the replacement positions the generated source bakes in, and
 * which of the aggregated slices the kernel may write and which it may read.
 *
 * <p>This is a property of the compiled kernel rather than of any one operation using it. It is
 * recorded alongside the shared instructions, and every operation reusing them verifies that its
 * own map {@link MemoryDataArgumentMap#reproduces(AggregateLayout) reproduces} the positions
 * before {@link MemoryDataArgumentMap#limitCopiesTo(AggregateLayout) limiting its copies} the
 * same way: with the same positions, the same replacements are the ones the kernel writes and
 * reads.</p>
 */
public class AggregateLayout {
	/** The replacement positions the compiled kernel addresses (see {@link MemoryDataArgumentMap#describeAggregatePositions()}). */
	private final String positions;
	/** The indices of the replacements whose slice the compiled kernel may write. */
	private final BitSet writtenSlices;
	/** The indices of the replacements whose slice the compiled kernel may read. */
	private final BitSet readSlices;

	/**
	 * Creates the layout of a compiled kernel's aggregate argument.
	 *
	 * @param positions     the replacement positions the compiled kernel addresses
	 * @param writtenSlices the indices of the replacements whose slice the kernel may write
	 * @param readSlices    the indices of the replacements whose slice the kernel may read
	 */
	public AggregateLayout(String positions, BitSet writtenSlices, BitSet readSlices) {
		this.positions = positions;
		this.writtenSlices = (BitSet) writtenSlices.clone();
		this.readSlices = (BitSet) readSlices.clone();
	}

	/**
	 * Returns the replacement positions the compiled kernel addresses.
	 *
	 * @return the baked position sequence
	 */
	public String getPositions() { return positions; }

	/**
	 * Returns the indices of the replacements whose slice the compiled kernel may write.
	 *
	 * @return a copy of the written slices
	 */
	public BitSet getWrittenSlices() { return (BitSet) writtenSlices.clone(); }

	/**
	 * Returns the indices of the replacements whose slice the compiled kernel may read.
	 *
	 * @return a copy of the read slices
	 */
	public BitSet getReadSlices() { return (BitSet) readSlices.clone(); }
}
