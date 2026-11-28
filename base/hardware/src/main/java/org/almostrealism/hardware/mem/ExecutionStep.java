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

import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One unit of an operation's execution whose internal order cannot be relied upon, together
 * with the memory it touches: the steps of an {@link OperationList} are the units its
 * {@link OperationList#get()} runs one after another, as listed by
 * {@link OperationList#getExecutionSteps()}.
 *
 * <p>A list that compiles into one kernel is a single step, because within a fused kernel
 * a read by one thread and a write by another are not ordered. A list that runs its members
 * one by one contributes the steps of each member, in order. Any other operation, with the
 * {@link io.almostrealism.compute.Isolated} subtrees that are evaluated while its arguments
 * are prepared, is a single step.</p>
 *
 * <p>The regions a step {@link #getTouched() touches} are every provider region of its
 * process tree, read or written, inline or isolated. They are complete only when the step
 * is {@link #isResolvable() resolvable}: a member with no visible tree may touch memory the
 * walk cannot see.</p>
 *
 * @see MemoryRegionList#touches(Supplier)
 * @see MemoryRegionList#destinations(Supplier)
 */
public class ExecutionStep {
	/** The operation this step runs. */
	private final Supplier<Runnable> operation;
	/** Every provider region of the operation's process tree. */
	private final MemoryRegionList touched;
	/** The regions the operation is known to write. */
	private final MemoryRegionList written;
	/** Whether every member of the operation exposes the memory it touches. */
	private final boolean resolvable;

	/**
	 * Creates the step that runs the given operation, resolving the memory it touches.
	 * Nothing is compiled or run.
	 *
	 * @param operation the operation the step runs
	 */
	public ExecutionStep(Supplier<Runnable> operation) {
		this.operation = operation;
		this.touched = MemoryRegionList.touches(operation);
		this.written = MemoryRegionList.destinations(operation);
		this.resolvable = isVisible(operation);
	}

	/**
	 * Returns the steps the given operation runs, in order: those of
	 * {@link OperationList#getExecutionSteps()} when it is a list, and otherwise the operation
	 * as a single step. Nothing is compiled or run.
	 *
	 * @param operation the operation whose steps are needed
	 * @return the steps of the operation
	 */
	public static List<ExecutionStep> of(Supplier<Runnable> operation) {
		if (operation instanceof OperationList) {
			return ((OperationList) operation).getExecutionSteps();
		}

		return List.of(new ExecutionStep(operation));
	}

	/**
	 * Returns the operation this step runs.
	 *
	 * @return the operation
	 */
	public Supplier<Runnable> getOperation() { return operation; }

	/**
	 * Returns every provider region of this step's process tree.
	 *
	 * @return the regions the step may read or write
	 */
	public MemoryRegionList getTouched() { return touched; }

	/**
	 * Returns the regions this step is known to write, as reported by
	 * {@link MemoryRegionList#destinations(Supplier)}.
	 *
	 * @return the regions the step writes
	 */
	public MemoryRegionList getWritten() { return written; }

	/**
	 * Returns whether {@link #getTouched()} is known to cover all the memory this step
	 * touches. It is not when a member of the step is not a {@link Process}, or its tree has
	 * a leaf that hides memory, such as a {@link MemoryDataCopy} created from raw suppliers or
	 * a computation argument produced by a closure over a collection: such a member may read
	 * or write memory no walk of the tree can find.
	 *
	 * @return true when every member of the step exposes the memory it touches
	 */
	public boolean isResolvable() { return resolvable; }

	/**
	 * Returns a description of the operation this step runs, for logging.
	 *
	 * @return the description
	 */
	public String describe() { return OperationInfo.display(operation); }

	/**
	 * Returns whether the given operation exposes all the memory it touches through its
	 * process tree: an {@link OperationList} does when each of its members does, and any
	 * other operation does when it is a {@link Process} whose whole tree is
	 * {@link #isVisibleTree(Process, Set) visible}.
	 *
	 * @param op the operation to inspect
	 * @return true when the operation's tree is visible
	 */
	private static boolean isVisible(Supplier<Runnable> op) {
		if (op instanceof OperationList) {
			for (Supplier<Runnable> member : (OperationList) op) {
				if (!isVisible(member)) return false;
			}

			return true;
		}

		if (!(op instanceof Process)) return false;
		return isVisibleTree((Process<?, ?>) op, Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	/**
	 * Returns whether every node of the given process tree exposes the memory it touches, so
	 * that {@link MemoryRegionList#touches(Supplier)} finds all of it: a provider of
	 * {@link MemoryData} is visible, since the walk records its region; any other node is
	 * visible when it reports no {@link Process#hasHiddenMemory() hidden memory} and all its
	 * children are visible.
	 *
	 * @param node    the process node to inspect
	 * @param visited the nodes already inspected, so that shared subtrees are visited once
	 * @return true when the tree beneath the node is visible
	 */
	private static boolean isVisibleTree(Process<?, ?> node, Set<Object> visited) {
		if (!visited.add(node)) return true;
		if (Provider.valueOf(node, MemoryData.class) != null) return true;
		if (node.hasHiddenMemory()) return false;

		Collection<? extends Process<?, ?>> children = node.getChildren();
		if (children == null) return true;

		for (Process<?, ?> child : children) {
			if (child == null || !isVisibleTree(child, visited)) return false;
		}

		return true;
	}
}
