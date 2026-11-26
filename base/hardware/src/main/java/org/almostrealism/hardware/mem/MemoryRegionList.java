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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A list of {@link MemoryData} regions touched by an operation, with overlap
 * semantics: two regions overlap when they refer to the same underlying
 * {@link io.almostrealism.code.Memory} and their absolute offset ranges intersect.
 *
 * <p>Region lists support dependency analysis between the members of a composite
 * operation. The two extraction forms reflect <em>when</em> memory is touched
 * relative to a member's own execution:</p>
 * <ul>
 *   <li>{@link #writes(Supplier)} — the regions a member writes when it runs,
 *       for members whose destinations are statically resolvable (an
 *       {@link Assignment} with a provider destination; a destination supplied by
 *       an opaque function cannot be resolved without running it, so such a
 *       member is not treated as a writer).</li>
 *   <li>{@link #dependentReads(Supplier)} — the regions read by a member's
 *       {@link Isolated} subtrees, which are evaluated during argument
 *       preparation, <em>before</em> the member's own kernel executes; a read of
 *       a region an earlier member wrote is the dependency that forces a
 *       subdivision.</li>
 * </ul>
 *
 * <p>Only providers are resolved during extraction (via the
 * {@link Computable#provider(Object)} flag and {@link Provider#get()}); nothing is
 * compiled or executed.</p>
 *
 * @see OperationList#subdivide()
 */
public class MemoryRegionList {
	/** The memory regions in this list. */
	private final List<MemoryData> regions;

	/**
	 * Creates an empty region list.
	 */
	public MemoryRegionList() {
		this.regions = new ArrayList<>();
	}

	/**
	 * Adds all of the given list's regions to this list.
	 *
	 * @param other the regions to include
	 */
	public void include(MemoryRegionList other) {
		regions.addAll(other.regions);
	}

	/**
	 * Removes all regions from this list.
	 */
	public void clear() {
		regions.clear();
	}

	/**
	 * Returns whether this region list is empty.
	 *
	 * @return true when no regions are present
	 */
	public boolean isEmpty() {
		return regions.isEmpty();
	}

	/**
	 * Returns whether any region of this list overlaps any region of the given
	 * list — the same {@link io.almostrealism.code.Memory} with intersecting
	 * absolute offset ranges.
	 *
	 * @param other the region list to compare against
	 * @return true when an overlapping pair of regions exists
	 */
	public boolean overlaps(MemoryRegionList other) {
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
	 * Returns the regions of this list, in the order they were added.
	 *
	 * @return an unmodifiable view of the regions
	 */
	public List<MemoryData> getRegions() {
		return Collections.unmodifiableList(regions);
	}

	/**
	 * Returns the memory regions the given operation writes, for operations whose
	 * destinations are statically resolvable ({@link Assignment}s with provider
	 * destinations, recursively including members of nested lists).
	 *
	 * @param op the operation to inspect
	 * @return the regions the operation writes, possibly empty
	 */
	public static MemoryRegionList writes(Supplier<Runnable> op) {
		MemoryRegionList result = new MemoryRegionList();
		appendWrites(op, result);
		return result;
	}

	/**
	 * Returns every provider region in the process tree of the given operation: the
	 * memory it may read or write, whether inline or beneath an {@link Isolated} node.
	 * A node whose value is a provider's {@link MemoryData} contributes that region and
	 * is not descended into; a subtree shared by several paths is visited once.
	 *
	 * <p>Members of an {@link OperationList} that are not {@link Process}es have no
	 * visible tree and contribute nothing, so a caller that needs every region an
	 * operation touches must first establish that each member is visible (see
	 * {@link ExecutionStep#isResolvable()}).</p>
	 *
	 * @param op the operation to inspect
	 * @return the regions the operation touches, possibly empty
	 */
	public static MemoryRegionList touches(Supplier<Runnable> op) {
		MemoryRegionList result = new MemoryRegionList();
		Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

		if (op instanceof OperationList) {
			for (Supplier<Runnable> member : (OperationList) op) {
				result.include(touches(member));
			}
		} else if (op instanceof Process) {
			appendTouches((Process<?, ?>) op, visited, result);
		}

		return result;
	}

	/**
	 * Returns the regions the given operation writes, as it declares them through
	 * {@link Process#getDestinations()}: every provider region beneath each destination, so
	 * that a destination which is a traversal or reshape of a provider still counts. Unlike
	 * {@link #writes(Supplier)}, which {@link OperationList#subdivide()} keeps using, this
	 * covers every operation that declares its destinations, not only an {@link Assignment}
	 * of a provider, including one wrapped by isolation or by a list.
	 *
	 * @param op the operation to inspect
	 * @return the regions the operation declares it writes, possibly empty
	 */
	public static MemoryRegionList destinations(Supplier<Runnable> op) {
		MemoryRegionList result = new MemoryRegionList();
		if (!(op instanceof Process)) return result;

		Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Process<?, ?> destination : ((Process<?, ?>) op).getDestinations()) {
			appendTouches(destination, visited, result);
		}

		return result;
	}

	/**
	 * Appends every provider region beneath the given process node to the provided list.
	 *
	 * @param node    the process node to inspect
	 * @param visited the nodes already inspected, so that shared subtrees are visited once
	 * @param result  receives the provider regions
	 */
	private static void appendTouches(Process<?, ?> node, Set<Object> visited, MemoryRegionList result) {
		if (!visited.add(node)) return;

		MemoryData value = Provider.valueOf(node, MemoryData.class);
		if (value != null) {
			result.regions.add(value);
			return;
		}

		Collection<? extends Process<?, ?>> children = node.getChildren();
		if (children == null) return;

		for (Process<?, ?> child : children) {
			appendTouches(child, visited, result);
		}
	}

	/**
	 * Returns the memory regions read by the given operation's {@link Isolated}
	 * subtrees — the reads that occur during argument preparation rather than
	 * inside the operation's own kernel, and hence the reads that can depend on a
	 * region an earlier member has written.
	 *
	 * @param op the operation to inspect
	 * @return the regions read ahead of the operation's execution, possibly empty
	 */
	public static MemoryRegionList dependentReads(Supplier<Runnable> op) {
		MemoryRegionList result = new MemoryRegionList();

		if (op instanceof OperationList) {
			for (Supplier<Runnable> member : (OperationList) op) {
				result.include(dependentReads(member));
			}
		} else if (op instanceof Process) {
			collectDependentReads((Process<?, ?>) op, false, result);
		}

		return result;
	}

	/**
	 * Recursively collects the write regions of the given operation into the
	 * provided list.
	 *
	 * @param op     the operation to inspect
	 * @param result receives the regions the operation writes
	 */
	private static void appendWrites(Supplier<Runnable> op, MemoryRegionList result) {
		if (op instanceof OperationList) {
			for (Supplier<Runnable> member : (OperationList) op) {
				appendWrites(member, result);
			}
		} else if (op instanceof Assignment) {
			MemoryData destination = Provider.valueOf(((Assignment<?>) op).getInputs().get(0), MemoryData.class);
			if (destination != null) result.regions.add(destination);
		}
	}

	/**
	 * Recursively collects the provider memory beneath {@link Isolated} nodes of the
	 * given process tree.
	 *
	 * @param node     the process node to inspect
	 * @param hoisted  whether an {@link Isolated} ancestor has already been encountered
	 * @param result   receives the regions read by isolated subtrees
	 */
	private static void collectDependentReads(Process<?, ?> node, boolean hoisted, MemoryRegionList result) {
		boolean isolated = hoisted || node instanceof Isolated;

		if (isolated) {
			MemoryData value = Provider.valueOf(node, MemoryData.class);
			if (value != null) {
				result.regions.add(value);
				return;
			}
		}

		Collection<? extends Process<?, ?>> children = node.getChildren();
		if (children == null) return;

		for (Process<?, ?> child : children) {
			collectDependentReads(child, isolated, result);
		}
	}
}
