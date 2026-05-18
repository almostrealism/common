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

import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Manages memory substitution for kernel arguments to optimize cross-provider transfers.
 *
 * <p>{@link MemoryReplacementManager} analyzes kernel arguments and replaces memory on incompatible
 * providers with temporary allocations on the target provider. This reduces overhead when multiple
 * small memory objects would otherwise require individual transfers.</p>
 *
 * <h2>Memory Aggregation Problem</h2>
 *
 * <p>When a kernel receives arguments from different memory providers, each transfer incurs overhead:</p>
 * <pre>
 * Naive Approach (slow):
 *   arg0: 100 bytes on CPU -> GPU transfer 1
 *   arg1: 50 bytes on CPU  -> GPU transfer 2
 *   arg2: 200 bytes on CPU -> GPU transfer 3
 *   Total: 3 separate transfers
 *
 * Aggregated Approach (fast):
 *   All CPU arguments -> Single contiguous temp buffer -> GPU transfer 1
 *   Total: 1 transfer for all arguments
 * </pre>
 *
 * <h2>Replacement Process</h2>
 *
 * <p>The manager performs three-phase processing:</p>
 *
 * <h3>1. Prepare Phase</h3>
 * <p>Copy original memory to temporary aggregated buffer:</p>
 * <pre>{@code
 * MemoryReplacementManager mgr = new MemoryReplacementManager(
 *     gpuProvider,
 *     (size, atomic) -> new Bytes(size, atomic)  // Temp factory
 * );
 *
 * Object[] args = {cpuMem1, cpuMem2, cpuMem3};
 * Object[] replaced = mgr.processArguments(args);
 *
 * // Execute prepare operations
 * mgr.getPrepare().get().run();
 * // Copies: cpuMem1 -> temp[0:100]
 * //         cpuMem2 -> temp[100:150]
 * //         cpuMem3 -> temp[150:350]
 * }</pre>
 *
 * <h3>2. Kernel Execution</h3>
 * <p>Execute kernel with replaced arguments:</p>
 * <pre>{@code
 * executeKernel(replaced);  // Uses temp buffer instead of original
 * }</pre>
 *
 * <h3>3. Postprocess Phase</h3>
 * <p>Copy results back to original memory:</p>
 * <pre>{@code
 * mgr.getPostprocess().get().run();
 * // Copies: temp[0:100] -> cpuMem1
 * //         temp[100:150] -> cpuMem2
 * //         temp[150:350] -> cpuMem3
 * }</pre>
 *
 * <h2>Aggregation Rules</h2>
 *
 * <p>Memory is replaced only if:</p>
 * <ul>
 *   <li>Argument is {@link MemoryData} (not primitive)</li>
 *   <li>Provider differs from target provider</li>
 *   <li>Size &lt;= aggregationThreshold (default: 1MB)</li>
 *   <li>No custom memory ordering (would break aggregation)</li>
 * </ul>
 *
 * <pre>{@code
 * // Arguments that ARE replaced:
 * MemoryData cpu100 = new Bytes(100);  // CPU provider, small
 * MemoryData cpu200 = new Bytes(200);  // CPU provider, small
 *
 * // Arguments NOT replaced:
 * MemoryData gpu500 = allocateGPU(500);  // Already on target provider
 * MemoryData cpu5mb = new Bytes(5 * 1024 * 1024);  // Too large
 * MemoryData reordered = withOrdering(data, ROW_MAJOR);  // Custom ordering
 * }</pre>
 *
 * <h2>Root Delegate Grouping</h2>
 *
 * <p>Arguments that share a root delegate are grouped together to minimize copies:</p>
 * <pre>{@code
 * MemoryData root = new Bytes(1000);
 * MemoryData view1 = root.range(0, 100);    // Offset 0
 * MemoryData view2 = root.range(500, 200);  // Offset 500
 *
 * // Both share same root -> Single temp allocation
 * // Temp covers min offset (0) to max offset (700)
 * MemoryData temp = new Bytes(700);  // One allocation for both
 * temp.range(0, 100)    // Replaces view1
 * temp.range(500, 200)  // Replaces view2
 * }</pre>
 *
 * <h2>Common Usage Pattern</h2>
 *
 * <pre>{@code
 * // Setup for GPU kernel execution
 * MemoryReplacementManager mgr = new MemoryReplacementManager(
 *     Hardware.getLocalHardware().getGPUMemory(),
 *     (size, atomic) -> allocateTempMemory(size)
 * );
 *
 * // Process arguments
 * Object[] originalArgs = {cpuData1, cpuData2, gpuData};
 * Object[] kernelArgs = mgr.processArguments(originalArgs);
 * // gpuData unchanged (already on GPU)
 * // cpuData1, cpuData2 replaced with temp memory
 *
 * // Execute kernel
 * mgr.getPrepare().get().run();      // CPU -> temp
 * kernel.execute(kernelArgs);         // Kernel sees temp
 * mgr.getPostprocess().get().run();   // temp -> CPU
 * }</pre>
 *
 * @see AcceleratedProcessDetails
 * @see MemoryDataCopy
 * @see OperationList
 */
public class MemoryReplacementManager implements ConsoleFeatures {
	/** Memory provider that arguments must reside in for the kernel to accept them without replacement. */
	private final MemoryProvider target;
	/** Factory for creating temporary buffers used during memory replacement. */
	private final TempMemoryFactory tempFactory;
	/** Maximum element count for a memory block to be eligible for aggregation and replacement. */
	private final int aggregationThreshold;

	/** Operations to copy data from original memory into temporary replacements before kernel execution. */
	private final OperationList prepare;
	/** Operations to copy results from temporary replacements back to original memory after kernel execution. */
	private final OperationList postprocess;

	/**
	 * Creates a replacement manager with a default aggregation threshold of 1M elements.
	 *
	 * @param target Target memory provider; arguments not in this provider will be replaced
	 * @param tempFactory Factory for creating temporary replacement buffers
	 */
	public MemoryReplacementManager(MemoryProvider target,
									TempMemoryFactory tempFactory) {
		this(target, tempFactory, 1024 * 1024);
	}

	/**
	 * Creates a replacement manager with a custom aggregation threshold.
	 *
	 * @param target Target memory provider; arguments not in this provider will be replaced
	 * @param tempFactory Factory for creating temporary replacement buffers
	 * @param aggregationThreshold Maximum element count for aggregation eligibility
	 */
	public MemoryReplacementManager(MemoryProvider target,
									TempMemoryFactory tempFactory,
									int aggregationThreshold) {
		this.target = target;
		this.tempFactory = tempFactory;
		this.aggregationThreshold = aggregationThreshold;

		this.prepare = new OperationList();
		this.postprocess = new OperationList();
	}

	/** Returns the pre-execution operation list that copies data into temporary replacements. */
	public OperationList getPrepare() { return prepare; }
	/** Returns the post-execution operation list that copies results back from temporary replacements. */
	public OperationList getPostprocess() { return postprocess; }
	/**
	 * Returns true if no replacements have been registered.
	 *
	 * @return True if both prepare and postprocess lists are empty
	 */
	public boolean isEmpty() {
		return prepare.isEmpty() && postprocess.isEmpty();
	}

	/**
	 * Processes an argument array, replacing memory blocks not in the target provider with temporary copies.
	 *
	 * <p>Groups arguments by their root delegate, then creates temporary buffers and
	 * registers pre/post-processing copy operations for each group.</p>
	 *
	 * @param args Kernel arguments to process
	 * @return New argument array with out-of-provider arguments replaced by temporary copies
	 */
	public Object[] processArguments(Object[] args) {
		Map<MemoryData, Replacement> replacements = new HashMap<>();

		Object[] result = new Object[args.length];

		i: for (int i = 0; i < args.length; i++) {
			Object arg = args[i];

			if (!(arg instanceof MemoryData data)) {
				result[i] = arg;
				continue i;
			}

			if (data.getMem() == null) {
				throw new IllegalArgumentException();
			} else if (data.getMemOrdering() != null) {
				warn("Reordered memory cannot be aggregated");
				result[i] = arg;
				continue i;
			} else if (data.getMem().getProvider() == target || data.getMemLength() > aggregationThreshold) {
				result[i] = arg;
				continue i;
			}

			Replacement replacement;

			if (replacements.containsKey(data.getRootDelegate())) {
				replacement = replacements.get(data.getRootDelegate());
			} else {
				replacement = new Replacement();
				replacement.root = data.getRootDelegate();
				replacement.children = new ArrayList<>();
				replacements.put(replacement.root, replacement);
			}

			replacement.children.add(data);
		}

		for (Replacement replacement : replacements.values()) {
			replacement.processChildren(tempFactory, (child, temp) -> {
				for (int i = 0; i < args.length; i++) {
					if (child == args[i]) {
						result[i] = temp;
					}
				}
			});
		}

		return result;
	}

	/**
	 * Represents a group of memory data blocks sharing a common root delegate, eligible for aggregation.
	 */
	protected class Replacement {
		/** The common root delegate for all children in this replacement group. */
		private MemoryData root;
		/** Memory data blocks within this group that need to be replaced. */
		private List<MemoryData> children;

		/**
		 * Processes this replacement group by creating a temporary buffer and registering copy operations.
		 *
		 * @param tempFactory Factory for creating the temporary buffer
		 * @param tempChildren Callback that maps each child to its temporary slice
		 */
		protected void processChildren(TempMemoryFactory tempFactory, BiConsumer<MemoryData, MemoryData> tempChildren) {
			int start = children.stream().mapToInt(MemoryData::getOffset).min().getAsInt();
			int end = children.stream().mapToInt(md -> md.getOffset() + md.getMemLength()).max().getAsInt();
			int length = end - start;

			MemoryData data = new Bytes(length, root, start);
			MemoryData tmp = tempFactory.apply(length, length);

			prepare.add(new MemoryDataCopy("Temp Prep", data, tmp));
			postprocess.add(new MemoryDataCopy("Temp Post", tmp, data));

			Bytes tempBytes = new Bytes(length, tmp, 0);

			for (MemoryData child : children) {
				tempChildren.accept(child, tempBytes.range(child.getOffset() - start, child.getMemLength(), child.getAtomicMemLength()));
			}
		}
	}

	/**
	 * Factory interface for creating temporary memory buffers used during argument replacement.
	 */
	public interface TempMemoryFactory {
		/**
		 * Creates a temporary {@link MemoryData} buffer for holding replacement data.
		 *
		 * @param memLength Total number of elements in the buffer
		 * @param atomicLength Number of elements per atomic unit (granularity)
		 * @return New temporary memory buffer
		 */
		MemoryData apply(int memLength, int atomicLength);
	}

	@Override
	public Console console() { return Hardware.console; }
}
