/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.kernel;

/**
 * Interface for configuring GPU/CPU kernel work dimensions.
 *
 * <p>Defines the parallelization parameters for hardware-accelerated operations:
 * global work size (total items), workgroup size (items per group), and work offset.
 * Every {@link io.almostrealism.code.Execution} is dispatched over the work range
 * described here, so whatever dispatches one sets its range without knowing which
 * backend compiled it.</p>
 *
 * <h2>Work Dimensions</h2>
 *
 * <ul>
 *   <li><strong>Global Work Size:</strong> Total number of work items (threads) to execute</li>
 *   <li><strong>Workgroup Size:</strong> Number of work items per workgroup (GPU block size)</li>
 *   <li><strong>Global Work Offset:</strong> Starting index for the work range</li>
 * </ul>
 *
 * <h2>GPU Execution Model</h2>
 *
 * <pre>
 * Global Work:   [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]  (globalWorkSize = 12)
 * Workgroups:    [0, 1, 2, 3][4, 5, 6, 7][8, 9, 10, 11]  (workgroupSize = 4)
 * Work Offset:   Start at index 0 (or custom offset)
 * </pre>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * Execution execution = instructions.get("function", 2);
 *
 * // Dispatch over 1000 work items, starting from the first
 * execution.setGlobalWorkOffset(0);
 * execution.setGlobalWorkSize(1000);
 * Semaphore done = execution.accept(new Object[] { output, input }, null);
 * }</pre>
 *
 * <h2>Implementation Notes</h2>
 *
 * <ul>
 *   <li><strong>Default work range:</strong> a single work item at offset 0, so an
 *       implementation that does not override the range methods refuses any other range</li>
 *   <li><strong>Default workgroup size:</strong> 1 (no workgroup parallelism)</li>
 *   <li><strong>OpenCL:</strong> Maps to global_work_size, local_work_size, global_work_offset</li>
 *   <li><strong>Metal:</strong> Maps to grid size and threadgroup size</li>
 *   <li><strong>JNI:</strong> Used for loop parallelization bounds</li>
 *   <li><strong>External process:</strong> A single work item, since the executable is
 *       given no work-item index</li>
 * </ul>
 *
 * <p>An implementation never dispatches over a range other than the one it was given. A range
 * it cannot dispatch over is refused, either by the setter, when the range it can perform is
 * fixed (an external process performs one work item), or when it is dispatched, when the limit
 * depends on the dispatch (the JNI backend's parallelism, or the size a Metal grid can
 * describe).</p>
 *
 * @see io.almostrealism.code.Execution
 */
public interface KernelWork {
	/**
	 * Returns the total number of work items to execute.
	 *
	 * <p>Default implementation returns 1, the single work item of work whose range is fixed.</p>
	 *
	 * @return Global work size (total threads/items)
	 */
	default long getGlobalWorkSize() {
		return 1;
	}

	/**
	 * Sets the total number of work items to execute.
	 *
	 * <p>Default implementation accepts only the single work item reported by the default
	 * {@link #getGlobalWorkSize()}, and refuses any other size.</p>
	 *
	 * @param globalWorkSize Global work size (total threads/items)
	 * @throws UnsupportedOperationException if this work cannot be dispatched over that many items
	 */
	default void setGlobalWorkSize(long globalWorkSize) {
		if (globalWorkSize != 1) {
			throw new UnsupportedOperationException(
					"This work performs a single work item, not " + globalWorkSize);
		}
	}

	/**
	 * Returns the workgroup size (local work size).
	 *
	 * <p>Default implementation returns 1 (no workgroup parallelism).
	 * Override to specify GPU block size or workgroup dimensions.</p>
	 *
	 * @return Number of work items per workgroup
	 */
	default int getWorkgroupSize() {
		return 1;
	}

	/**
	 * Returns the starting index offset for the work range.
	 *
	 * <p>Default implementation returns 0, the first work item.</p>
	 *
	 * @return Global work offset (starting index)
	 */
	default long getGlobalWorkOffset() {
		return 0;
	}

	/**
	 * Sets the starting index offset for the work range.
	 *
	 * <p>Default implementation accepts only the first work item, matching the default
	 * {@link #getGlobalWorkOffset()}, and refuses any other offset.</p>
	 *
	 * @param globalWorkOffset Global work offset (starting index)
	 * @throws UnsupportedOperationException if this work cannot be dispatched from that index
	 */
	default void setGlobalWorkOffset(long globalWorkOffset) {
		if (globalWorkOffset != 0) {
			throw new UnsupportedOperationException(
					"This work performs only the first work item, not item " + globalWorkOffset);
		}
	}
}
