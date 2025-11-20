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

package org.almostrealism.hardware.kernel;

/**
 * Interface for configuring GPU/CPU kernel work dimensions.
 *
 * <p>Defines the parallelization parameters for hardware-accelerated operations:
 * global work size (total items), workgroup size (items per group), and work offset.</p>
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
 * class MyOperation implements KernelWork {
 *     private long globalWorkSize;
 *     private long globalWorkOffset;
 *
 *     public long getGlobalWorkSize() { return globalWorkSize; }
 *     public void setGlobalWorkSize(long size) { this.globalWorkSize = size; }
 *     public long getGlobalWorkOffset() { return globalWorkOffset; }
 *     public void setGlobalWorkOffset(long offset) { this.globalWorkOffset = offset; }
 * }
 * }</pre>
 *
 * <h2>Implementation Notes</h2>
 *
 * <ul>
 *   <li><strong>Default workgroup size:</strong> 1 (no workgroup parallelism)</li>
 *   <li><strong>OpenCL:</strong> Maps to global_work_size, local_work_size, global_work_offset</li>
 *   <li><strong>Metal:</strong> Maps to grid size and threadgroup size</li>
 *   <li><strong>JNI:</strong> Used for loop parallelization bounds</li>
 * </ul>
 *
 * @see org.almostrealism.hardware.HardwareOperator
 * @see org.almostrealism.hardware.cl.CLOperator
 * @see org.almostrealism.hardware.metal.MetalOperator
 */
public interface KernelWork {
	/**
	 * Returns the total number of work items to execute.
	 *
	 * @return Global work size (total threads/items)
	 */
	long getGlobalWorkSize();

	/**
	 * Sets the total number of work items to execute.
	 *
	 * @param globalWorkSize Global work size (total threads/items)
	 */
	void setGlobalWorkSize(long globalWorkSize);

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
	 * @return Global work offset (starting index)
	 */
	long getGlobalWorkOffset();

	/**
	 * Sets the starting index offset for the work range.
	 *
	 * @param globalWorkOffset Global work offset (starting index)
	 */
	void setGlobalWorkOffset(long globalWorkOffset);
}
