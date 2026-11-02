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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p>Copy original memory to temporary aggregated buffer, chaining on any prior work:</p>
 * <pre>{@code
 * MemoryReplacementManager mgr = new MemoryReplacementManager(
 *     context,
 *     gpuProvider,
 *     (size, atomic) -> new Bytes(size, atomic)  // Temp factory
 * );
 *
 * Object[] args = {cpuMem1, cpuMem2, cpuMem3};
 * Object[] replaced = mgr.processArguments(args);
 *
 * // Issue prepare copies, chained on the caller's prior completion
 * Semaphore prepared = Submittable.submit(mgr.getPrepareOperations(), dependsOn);
 * // Copies: cpuMem1 -> temp[0:100]
 * //         cpuMem2 -> temp[100:150]
 * //         cpuMem3 -> temp[150:350]
 * }</pre>
 *
 * <h3>2. Kernel Execution</h3>
 * <p>Execute kernel with replaced arguments, chained on the prepare copies:</p>
 * <pre>{@code
 * Semaphore done = executeKernel(replaced, prepared);  // Uses temp buffer
 * }</pre>
 *
 * <h3>3. Postprocess Phase</h3>
 * <p>Copy results back to original memory, chained on the kernel's completion:</p>
 * <pre>{@code
 * Semaphore complete = Submittable.submit(mgr.getPostprocessOperations(), done);
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
 *     context,
 *     context.getDataContext().getKernelMemoryProvider(),
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
 * Semaphore prepared = Submittable.submit(mgr.getPrepareOperations(), null); // CPU -> temp
 * Semaphore done = kernel.execute(kernelArgs, prepared);                     // Kernel sees temp
 * Submittable.submit(mgr.getPostprocessOperations(), done);                  // temp -> CPU
 * }</pre>
 *
 * <p>Each copy runs through the serving {@link ComputeContext}'s
 * {@link ComputeContext#copy(Object, Object, io.almostrealism.streams.Semaphore) copy},
 * so the context chooses the mechanism and the sequencing: a batching context queues the
 * copy onto its command buffer ordered after {@code dependsOn}; any other context waits
 * for {@code dependsOn} and copies directly. The one exception is the copy of a read-only
 * source into its temporary, which depends on no prior work: it is
 * {@link Submittable#unordered() made at once}, and its completion still covers
 * {@code dependsOn}, so the kernel chained on it is ordered exactly as it would be without
 * a replacement.</p>
 *
 * @see AcceleratedProcessDetails
 * @see ComputeContext
 */
public class MemoryReplacementManager implements ConsoleFeatures {
	/**
	 * The {@link ComputeContext} of the operation this manager prepares arguments for. The
	 * prepare/postprocess copies run through its
	 * {@link ComputeContext#copy(Object, Object, io.almostrealism.streams.Semaphore) copy},
	 * so the context the kernel runs on chooses how the replacement data moves and how the
	 * copies are sequenced.
	 */
	private final ComputeContext<MemoryData> context;
	/** Memory provider that arguments must reside in for the kernel to accept them without replacement. */
	private final MemoryProvider target;
	/** Factory for creating temporary buffers used during memory replacement. */
	private final TempMemoryFactory tempFactory;
	/** Maximum element count for a memory block to be eligible for aggregation and replacement. */
	private final int aggregationThreshold;

	/** Copies that move data from original memory into temporary replacements before kernel execution. */
	private final List<Submittable> prepare;
	/** Copies that move results from temporary replacements back to original memory after kernel execution. */
	private final List<Submittable> postprocess;
	/** Temporary buffers created by {@link #processArguments(Object[])}, freed by {@link #releaseTemporaries()}. */
	private final List<MemoryData> temporaries;

	/**
	 * Creates a replacement manager with a default aggregation threshold of 1M elements.
	 *
	 * @param context The {@link ComputeContext} whose copy mechanism moves replacement data
	 * @param target Target memory provider; arguments not in this provider will be replaced
	 * @param tempFactory Factory for creating temporary replacement buffers
	 */
	public MemoryReplacementManager(ComputeContext<MemoryData> context,
									MemoryProvider target,
									TempMemoryFactory tempFactory) {
		this(context, target, tempFactory, 1024 * 1024);
	}

	/**
	 * Creates a replacement manager with a custom aggregation threshold.
	 *
	 * @param context The {@link ComputeContext} whose copy mechanism moves replacement data
	 * @param target Target memory provider; arguments not in this provider will be replaced
	 * @param tempFactory Factory for creating temporary replacement buffers
	 * @param aggregationThreshold Maximum element count for aggregation eligibility
	 */
	public MemoryReplacementManager(ComputeContext<MemoryData> context,
									MemoryProvider target,
									TempMemoryFactory tempFactory,
									int aggregationThreshold) {
		this.context = context;
		this.target = target;
		this.tempFactory = tempFactory;
		this.aggregationThreshold = aggregationThreshold;

		this.prepare = new ArrayList<>();
		this.postprocess = new ArrayList<>();
		this.temporaries = new ArrayList<>();
	}

	/** Returns the pre-execution copies that move data into temporary replacements. */
	public List<Submittable> getPrepareOperations() { return prepare; }
	/** Returns the post-execution copies that move results back from temporary replacements. */
	public List<Submittable> getPostprocessOperations() { return postprocess; }
	/**
	 * Returns true if no replacements have been registered.
	 *
	 * @return True if both prepare and postprocess lists are empty
	 */
	public boolean isEmpty() {
		return prepare.isEmpty() && postprocess.isEmpty();
	}

	/**
	 * Frees the temporary buffers this manager created, exactly once.
	 *
	 * <p>A manager serves a single invocation, and its temporaries are read only by that
	 * invocation's prepare copies, kernel and postprocess copies; results are taken from the
	 * original arguments, never from a temporary. Once that chain has completed nothing can
	 * use them again, so they are released here rather than left for the garbage collector:
	 * a temporary's Java handle is small, so collection gives no signal of the device memory
	 * it holds, and an operation dispatched repeatedly would otherwise exhaust that memory
	 * long before the collector runs.</p>
	 *
	 * <p>Every temporary is destroyed even if destroying an earlier one fails: the first such
	 * failure is rethrown once all have been attempted, with any later ones attached to it as
	 * suppressed.</p>
	 *
	 * <p>Call only once the invocation's full completion chain has fired.</p>
	 */
	public void releaseTemporaries() {
		List<MemoryData> released;

		synchronized (temporaries) {
			released = new ArrayList<>(temporaries);
			temporaries.clear();
		}

		Destroyable.releaseAll(released.stream().map(tmp -> (Runnable) tmp::destroy).toList());
	}

	/**
	 * Processes an argument array, replacing memory blocks not in the target provider with temporary copies.
	 *
	 * <p>Groups arguments by their root delegate, then creates temporary buffers and
	 * registers pre/post-processing copy operations for each group.</p>
	 *
	 * <p>If creating a temporary fails, the temporaries already created are freed and the
	 * copies registered so far are discarded before the failure propagates, leaving the
	 * manager with nothing to run and nothing to release.</p>
	 *
	 * @param args Kernel arguments to process
	 * @return New argument array with out-of-provider arguments replaced by temporary copies
	 */
	public Object[] processArguments(Object[] args) {
		Map<MemoryData, Replacement> replacements = new HashMap<>();
		Replacement[] replacedBy = new Replacement[args.length];

		for (int i = 0; i < args.length; i++) {
			if (!(args[i] instanceof MemoryData data) || !requiresReplacement(data)) continue;

			Replacement replacement = replacements.get(data.getRootDelegate());

			if (replacement == null) {
				replacement = new Replacement(data.getRootDelegate());
				replacements.put(data.getRootDelegate(), replacement);
			}

			replacement.add(data);
			replacedBy[i] = replacement;
		}

		try {
			for (Replacement replacement : replacements.values()) {
				replacement.stage();
			}
		} catch (RuntimeException | Error e) {
			// No completion chain will ever release what was created before the failure (most
			// often a memory-limit failure, which retaining those buffers would only prolong),
			// and the copies registered against them must not run.
			prepare.clear();
			postprocess.clear();

			try {
				releaseTemporaries();
			} catch (RuntimeException | Error releaseFailure) {
				if (e != releaseFailure) e.addSuppressed(releaseFailure);
			}

			throw e;
		}

		Object[] result = args.clone();

		for (int i = 0; i < args.length; i++) {
			if (replacedBy[i] != null && args[i] instanceof MemoryData data) {
				result[i] = replacedBy[i].replacementFor(data);
			}
		}

		return result;
	}

	/**
	 * Returns whether a kernel argument must be staged in a temporary on the {@link #target}
	 * provider: it lives on another provider and is no larger than the
	 * {@link #aggregationThreshold}. Memory with a custom ordering is left where it is, because
	 * it cannot be aggregated.
	 *
	 * @param data a kernel argument
	 * @return true if the argument is replaced by a slice of a temporary
	 * @throws IllegalArgumentException if {@code data} has no memory
	 */
	private boolean requiresReplacement(MemoryData data) {
		if (data.getMem() == null) {
			throw new IllegalArgumentException();
		} else if (data.getMemOrdering() != null) {
			warn("Reordered memory cannot be aggregated");
			return false;
		}

		return data.getMem().getProvider() != target && data.getMemLength() <= aggregationThreshold;
	}

	/**
	 * The arguments that view one root delegate, which are staged together in a single
	 * temporary on the {@link #target} provider spanning all of them.
	 */
	private class Replacement {
		/** The root delegate that every argument in this group views. */
		private final MemoryData root;
		/** The arguments in this group. */
		private final List<MemoryData> children;
		/** The offset into {@link #root} at which the staged range begins; set by {@link #stage()}. */
		private int start;
		/** The temporary holding the staged range of {@link #root}; set by {@link #stage()}. */
		private Bytes temporary;

		/**
		 * Creates an empty group for the arguments that view {@code root}.
		 *
		 * @param root the root delegate of the arguments in the group
		 */
		Replacement(MemoryData root) {
			this.root = root;
			this.children = new ArrayList<>();
		}

		/**
		 * Adds an argument that views this group's root delegate.
		 *
		 * @param child the argument
		 */
		void add(MemoryData child) {
			children.add(child);
		}

		/**
		 * Creates this group's temporary, spanning every argument in the group, and registers
		 * the copy that moves the staged range of the root into it before the kernel runs and,
		 * when the root can be written, the copy that moves it back afterward.
		 *
		 * <p>A group whose root is read-only is copied in and not back out.
		 * Nothing can have written to it, so there is no result to carry back,
		 * and registering the copy anyway does not fail here where it is
		 * decided: it fails when it runs, on whichever thread the operation
		 * completed on, once for every operation reading the same asset. Model
		 * weights are read this way, so that is per layer, per token.</p>
		 *
		 * <p>For the same reason the copy in of a read-only group is
		 * {@link Submittable#unordered() not ordered} after the work the operation depends on. A
		 * copy is ordered after that work so that it neither reads a source the work is still
		 * producing nor overwrites a destination the work is still reading. No work produces a
		 * read-only source, and the destination is the temporary created here for this invocation
		 * alone, so neither can happen: the copy is made at once, while its completion still covers
		 * the dependency, so the kernel is ordered after that dependency exactly as it would be
		 * without a replacement, whatever completion the context reports for the copy. Ordering
		 * the copy itself after the dependency would gain nothing and cost a great deal on a
		 * batching backend, since a context that cannot move this memory itself (a Metal context
		 * reading a file-backed asset, for example) completes the copy on the host once the
		 * dependency has completed, and the kernel ordered after that host work cannot be
		 * batched with the device work before it.</p>
		 */
		void stage() {
			start = children.stream().mapToInt(MemoryData::getOffset).min().getAsInt();
			int end = children.stream().mapToInt(md -> md.getOffset() + md.getMemLength()).max().getAsInt();
			int length = end - start;

			MemoryData source = new Bytes(length, root, start);
			MemoryData tmp = tempFactory.apply(length, length);

			synchronized (temporaries) {
				temporaries.add(tmp);
			}

			Submittable copyIn = dependsOn -> context.copy(source, tmp, dependsOn);

			if (root.isReadOnly()) {
				prepare.add(copyIn.unordered());
			} else {
				prepare.add(copyIn);
				postprocess.add(dependsOn -> context.copy(tmp, source, dependsOn));
			}

			temporary = new Bytes(length, tmp, 0);
		}

		/**
		 * Returns the slice of this group's temporary that stands in for an argument of the
		 * group once it has been {@link #stage() staged}.
		 *
		 * @param child an argument added to this group
		 * @return the view of the temporary at the argument's position within the root
		 */
		MemoryData replacementFor(MemoryData child) {
			return temporary.range(child.getOffset() - start, child.getMemLength(), child.getAtomicMemLength());
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
