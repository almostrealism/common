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
	private final MemoryProvider target;
	private final TempMemoryFactory tempFactory;
	private final int aggregationThreshold;

	private final OperationList prepare;
	private final OperationList postprocess;

	public MemoryReplacementManager(MemoryProvider target,
									TempMemoryFactory tempFactory) {
		this(target, tempFactory, 1024 * 1024);
	}

	public MemoryReplacementManager(MemoryProvider target,
									TempMemoryFactory tempFactory,
									int aggregationThreshold) {
		this.target = target;
		this.tempFactory = tempFactory;
		this.aggregationThreshold = aggregationThreshold;

		this.prepare = new OperationList();
		this.postprocess = new OperationList();
	}

	public OperationList getPrepare() { return prepare; }
	public OperationList getPostprocess() { return postprocess; }
	public boolean isEmpty() {
		return prepare.isEmpty() && postprocess.isEmpty();
	}

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

	protected class Replacement {
		private MemoryData root;
		private List<MemoryData> children;

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

	public interface TempMemoryFactory {
		MemoryData apply(int memLength, int atomicLength);
	}

	@Override
	public Console console() { return Hardware.console; }
}
