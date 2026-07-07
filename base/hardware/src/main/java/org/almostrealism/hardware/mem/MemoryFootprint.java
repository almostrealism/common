/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.mem;

import io.almostrealism.compute.Isolated;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * A set of {@link MemoryData} regions touched by an operation, with overlap
 * semantics: two regions overlap when they refer to the same underlying
 * {@link io.almostrealism.code.Memory} and their absolute offset ranges intersect.
 *
 * <p>Footprints support dependency analysis between the members of a composite
 * operation. The two extraction forms reflect <em>when</em> memory is touched
 * relative to a member's own execution:</p>
 * <ul>
 *   <li>{@link #writes(Supplier)} — the regions a member writes when it runs,
 *       for members whose destinations are statically resolvable (an
 *       {@link Assignment} with a provider destination; a destination supplied by
 *       an opaque function cannot be resolved without running it, so such a
 *       member is not treated as a writer).</li>
 *   <li>{@link #hoistedReads(Supplier)} — the regions read by a member's
 *       {@link Isolated} subtrees, which are evaluated during argument
 *       preparation, <em>before</em> the member's own kernel executes.</li>
 * </ul>
 *
 * <p>Only providers are resolved during extraction (via the
 * {@link Computable#provider(Object)} flag and {@link Provider#get()}); nothing is
 * compiled or executed.</p>
 *
 * @see OperationList#subdivide()
 */
public class MemoryFootprint {
	/** The memory regions in this footprint. */
	private final List<MemoryData> regions;

	/**
	 * Creates an empty footprint.
	 */
	public MemoryFootprint() {
		this.regions = new ArrayList<>();
	}

	/**
	 * Adds all of the given footprint's regions to this footprint.
	 *
	 * @param footprint the regions to include
	 */
	public void include(MemoryFootprint footprint) {
		regions.addAll(footprint.regions);
	}

	/**
	 * Removes all regions from this footprint.
	 */
	public void clear() {
		regions.clear();
	}

	/**
	 * Returns whether this footprint is empty.
	 *
	 * @return true when no regions are present
	 */
	public boolean isEmpty() {
		return regions.isEmpty();
	}

	/**
	 * Returns whether any region of this footprint overlaps any region of the
	 * given footprint — the same {@link io.almostrealism.code.Memory} with
	 * intersecting absolute offset ranges.
	 *
	 * @param other the footprint to compare against
	 * @return true when an overlapping pair of regions exists
	 */
	public boolean overlaps(MemoryFootprint other) {
		for (MemoryData a : regions) {
			for (MemoryData b : other.regions) {
				if (a.getMem() == b.getMem() &&
						a.getOffset() < b.getOffset() + b.getMemLength() &&
						b.getOffset() < a.getOffset() + a.getMemLength()) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Returns the memory regions the given operation writes, for operations whose
	 * destinations are statically resolvable ({@link Assignment}s with provider
	 * destinations, recursively including members of nested lists).
	 *
	 * @param op the operation to inspect
	 * @return the regions the operation writes, possibly empty
	 */
	public static MemoryFootprint writes(Supplier<Runnable> op) {
		MemoryFootprint footprint = new MemoryFootprint();
		collectWrites(op, footprint);
		return footprint;
	}

	/**
	 * Returns the memory regions read by the given operation's {@link Isolated}
	 * subtrees — the reads that occur during argument preparation rather than
	 * inside the operation's own kernel.
	 *
	 * @param op the operation to inspect
	 * @return the regions read ahead of the operation's execution, possibly empty
	 */
	public static MemoryFootprint hoistedReads(Supplier<Runnable> op) {
		MemoryFootprint footprint = new MemoryFootprint();

		if (op instanceof OperationList) {
			for (Supplier<Runnable> member : (OperationList) op) {
				footprint.include(hoistedReads(member));
			}
		} else if (op instanceof Process) {
			collectHoistedReads((Process<?, ?>) op, false, footprint);
		}

		return footprint;
	}

	/**
	 * Recursively collects the write regions of the given operation into the
	 * provided footprint.
	 *
	 * @param op        the operation to inspect
	 * @param footprint receives the regions the operation writes
	 */
	private static void collectWrites(Supplier<Runnable> op, MemoryFootprint footprint) {
		if (op instanceof OperationList) {
			for (Supplier<Runnable> member : (OperationList) op) {
				collectWrites(member, footprint);
			}
		} else if (op instanceof Assignment) {
			MemoryData destination = providerValue(((Assignment<?>) op).getInputs().get(0));
			if (destination != null) footprint.regions.add(destination);
		}
	}

	/**
	 * Recursively collects the provider memory beneath {@link Isolated} nodes of the
	 * given process tree.
	 *
	 * @param node      the process node to inspect
	 * @param hoisted   whether an {@link Isolated} ancestor has already been encountered
	 * @param footprint receives the regions read by isolated subtrees
	 */
	private static void collectHoistedReads(Process<?, ?> node, boolean hoisted, MemoryFootprint footprint) {
		boolean isolated = hoisted || node instanceof Isolated;

		if (isolated) {
			MemoryData value = providerValue(node);
			if (value != null) {
				footprint.regions.add(value);
				return;
			}
		}

		Collection<? extends Process<?, ?>> children = node.getChildren();
		if (children == null) return;

		for (Process<?, ?> child : children) {
			collectHoistedReads(child, isolated, footprint);
		}
	}

	/**
	 * Resolves the memory behind a provider, without compiling or executing anything.
	 *
	 * @param producer the producer to resolve
	 * @return the provider's memory, or {@code null} when the producer is not a
	 *         provider or does not hold {@link MemoryData}
	 */
	private static MemoryData providerValue(Object producer) {
		if (!Computable.provider(producer) || !(producer instanceof Supplier)) return null;

		Object evaluable = ((Supplier<?>) producer).get();
		if (!(evaluable instanceof Provider)) return null;

		Object value = ((Provider<?>) evaluable).get();
		return value instanceof MemoryData ? (MemoryData) value : null;
	}
}
