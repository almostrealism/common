package org.almostrealism.hardware.mem;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.concurrent.CompletionConsumer;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.FixedEvaluable;
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Producer;
import io.almostrealism.streams.StreamingEvaluable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Operation for copying data between {@link MemoryData} instances with optional offset support.
 *
 * <p>{@link MemoryDataCopy} implements a memory copy operation that transfers data from a source
 * {@link MemoryData} to a target {@link MemoryData}. It supports dynamic source/target resolution
 * via {@link Supplier} and can copy ranges within the memory buffers.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <p>Copy entire contents from source to target:</p>
 * <pre>{@code
 * MemoryData source = new Bytes(1000);
 * MemoryData target = new Bytes(1000);
 *
 * // Fill source with data
 * source.setMem(0, new double[]{1, 2, 3, 4, 5});
 *
 * // Create copy operation
 * MemoryDataCopy copy = new MemoryDataCopy("My Copy", source, target);
 *
 * // Execute copy
 * copy.get().run();
 *
 * // target now contains: {1, 2, 3, 4, 5, ...}
 * }</pre>
 *
 * <h2>Range Copying</h2>
 *
 * <p>Copy specific ranges with source/target offsets:</p>
 * <pre>{@code
 * MemoryData source = new Bytes(1000);
 * MemoryData target = new Bytes(1000);
 *
 * // Copy 100 elements from source[50] to target[200]
 * MemoryDataCopy copy = new MemoryDataCopy(
 *     "Range Copy",
 *     () -> source,
 *     () -> target,
 *     50,   // sourcePosition
 *     200,  // targetPosition
 *     100   // length
 * );
 *
 * copy.get().run();
 * // Copies: source[50:150] -> target[200:300]
 * }</pre>
 *
 * <h2>Dynamic Resolution</h2>
 *
 * <p>Source and target are resolved lazily via {@link Supplier}, enabling dynamic memory selection:</p>
 * <pre>{@code
 * // Source/target determined at execution time
 * MemoryDataCopy copy = new MemoryDataCopy(
 *     "Dynamic Copy",
 *     () -> getCurrentSource(),  // Resolved when copy executes
 *     () -> getCurrentTarget(),
 *     1000
 * );
 *
 * // Later, when executed
 * copy.get().run();  // Calls suppliers to get current source/target
 * }</pre>
 *
 * <h2>Integration with MemoryReplacementManager</h2>
 *
 * <p>Used extensively for prepare/postprocess phases:</p>
 * <pre>{@code
 * // Prepare: Copy original -> temp
 * MemoryDataCopy prepare = new MemoryDataCopy(
 *     "Temp Prep",
 *     originalData,
 *     tempData
 * );
 * prepare.get().run();
 *
 * // ... kernel execution with temp data ...
 *
 * // Postprocess: Copy temp -> original
 * MemoryDataCopy postprocess = new MemoryDataCopy(
 *     "Temp Post",
 *     tempData,
 *     originalData
 * );
 * postprocess.get().run();
 * }</pre>
 *
 * <h2>Operation Metadata and Profiling</h2>
 *
 * <p>Implements {@link OperationInfo} for profiling integration:</p>
 * <pre>{@code
 * MemoryDataCopy copy = new MemoryDataCopy("Profile Test", source, target, 1000);
 *
 * System.out.println(copy.describe());  // "Profile Test (Copy 1000 values)"
 * System.out.println(copy.getOutputSize());  // 1000
 * }</pre>
 *
 * <h2>Implementation Details</h2>
 *
 * <p>The copy is performed by the {@link io.almostrealism.code.ComputeContext} of the
 * {@link io.almostrealism.code.DataContext} that manages the target's memory (identified
 * via its {@link io.almostrealism.code.MemoryProvider} &mdash; see
 * {@link org.almostrealism.hardware.Hardware#getComputeContext(io.almostrealism.code.Memory)}),
 * so the backend that owns the memory chooses the mechanism. When no configured context
 * manages the memory (plain JVM heap, for example), a host-mediated {@code toArray}/
 * {@code setMem} transfer is used instead.</p>
 *
 * <h2>Membership in an Operation List</h2>
 *
 * <p>The {@link Runnable} returned by {@link #get()} is also {@link Submittable}, so an
 * {@link org.almostrealism.hardware.OperationList} that runs its members separately chains a
 * copy like any compiled kernel: the copy is issued after the previous member's completion
 * without waiting for it on the host, and its own completion is waited only where the list
 * ends or a member that cannot be chained needs everything ahead of it to be complete. Under a
 * batching backend (Metal) a copy that could only run synchronously cost two command-buffer
 * commits and host round trips (one for everything issued ahead of it, one for itself); a
 * chained copy is encoded into the open command buffer alongside its neighbours. See
 * {@link Runner} for how the source and target are resolved without reading memory that pending
 * work is still producing.</p>
 *
 * <h2>Verbose Logging</h2>
 *
 * <p>Enable verbose output for debugging:</p>
 * <pre>{@code
 * MemoryDataCopy.enableVerbose = true;
 *
 * copy.get().run();
 * // Output:
 * // MemoryDataCopy[My Copy]: Copying Bytes[1000] (0) to Bytes[1000] (0) [1000]
 * }</pre>
 *
 * @see MemoryData
 * @see Process
 *
 * @deprecated {@link org.almostrealism.hardware.computations.Assignment} is the tool for
 * assigning one value to another, and it recognizes the plain-memory-copy case (routing it
 * through the {@link io.almostrealism.code.ComputeContext}'s copy, which chains on the
 * {@link io.almostrealism.streams.Semaphore} mechanism like every other operation).
 * Prefer {@code Assignment} (directly, or via the {@code copy(...)} helpers on
 * {@code MemoryDataFeatures}) wherever it can be used. Where it cannot yet take this class's
 * place, keep using {@link MemoryDataCopy} rather than open-coding a replacement &mdash; it
 * keeps every reference to the fallback path in one place, and it already targets the
 * {@link io.almostrealism.code.ComputeContext} that manages the memory being written.
 * Its execution accepts and publishes a completion {@link io.almostrealism.streams.Semaphore}
 * (see {@link Runner}), so keeping it in an operation list costs no host wait.
 */
@Deprecated
public class MemoryDataCopy implements ParallelProcess<Process<?, Runnable>, Runnable>, OperationInfo, ConsoleFeatures {
	/** If true, logs each copy operation to stdout including source, target, and length. */
	public static boolean enableVerbose = false;

	/** Metadata describing this copy operation for profiling and display. */
	private OperationMetadata metadata;
	/** Supplier for the source memory data to copy from. */
	private Supplier<MemoryData> source;
	/** Supplier for the target memory data to copy into. */
	private Supplier<MemoryData> target;
	/**
	 * Producer reference for the source when constructed from a {@link Producer}.
	 * When non-null, it is exposed as a child {@link Process} so that the
	 * optimization cascade can see into the producer tree that supplies this
	 * copy's source memory. When null, the source was provided as a raw
	 * {@link Supplier} and has no producer-tree structure to expose.
	 */
	private Producer<? extends MemoryData> sourceProducer;
	/** Producer reference for the target; see {@link #sourceProducer}. */
	private Producer<? extends MemoryData> targetProducer;
	/** Element offset within the source from which copying begins. */
	private int sourcePosition;
	/** Element offset within the target at which copying begins. */
	private int targetPosition;
	/** Number of elements to copy. */
	private int length;

	/**
	 * Creates a copy operation between two fixed memory data instances, copying all elements.
	 *
	 * @param name   Display name for this copy operation
	 * @param source Source memory data
	 * @param target Target memory data
	 */
	public MemoryDataCopy(String name, MemoryData source, MemoryData target) {
		// TODO(review): fixed operands become raw Suppliers, so Runner treats them as unordered and host-waits dependsOn
		this(name, () -> source, () -> target, 0, 0, source.getMemLength());
	}

	/**
	 * Creates an unnamed copy operation between supplier-backed memory data, copying from the start.
	 *
	 * @param source Supplier for source memory data
	 * @param target Supplier for target memory data
	 * @param length Number of elements to copy
	 */
	public MemoryDataCopy(Supplier<MemoryData> source, Supplier<MemoryData> target, int length) {
		this(null, source, target, 0, 0, length);
	}

	/**
	 * Creates a copy operation between supplier-backed memory data, copying from the start.
	 *
	 * @param name   Display name for this copy operation
	 * @param source Supplier for source memory data
	 * @param target Supplier for target memory data
	 * @param length Number of elements to copy
	 */
	public MemoryDataCopy(String name, Supplier<MemoryData> source, Supplier<MemoryData> target, int length) {
		this(name, source, target, 0, 0, length);
	}

	/**
	 * Creates an unnamed copy operation with explicit source and target offsets.
	 *
	 * @param source         Supplier for source memory data
	 * @param target         Supplier for target memory data
	 * @param sourcePosition Offset within the source to begin copying
	 * @param targetPosition Offset within the target to begin copying
	 * @param length         Number of elements to copy
	 */
	public MemoryDataCopy(Supplier<MemoryData> source, Supplier<MemoryData> target, int sourcePosition, int targetPosition, int length) {
		this(null, source, target, sourcePosition, targetPosition, length);
	}

	/**
	 * Creates a copy operation with a display name and explicit source and target offsets.
	 *
	 * @param name           Display name for this copy operation
	 * @param source         Supplier for source memory data
	 * @param target         Supplier for target memory data
	 * @param sourcePosition Offset within the source to begin copying
	 * @param targetPosition Offset within the target to begin copying
	 * @param length         Number of elements to copy
	 */
	public MemoryDataCopy(String name, Supplier<MemoryData> source, Supplier<MemoryData> target, int sourcePosition, int targetPosition, int length) {
		this.metadata = new OperationMetadata("copy_" + length, name, "Copy " + length + " values");
		this.source = source;
		this.target = target;
		this.sourcePosition = sourcePosition;
		this.targetPosition = targetPosition;
		this.length = length;
	}

	/**
	 * Creates a copy operation from {@link Producer}-backed source and target.
	 *
	 * <p>Retaining the {@link Producer} references (rather than only their
	 * resolved {@link MemoryData} suppliers) lets the optimization cascade
	 * see into the producer trees through {@link #getChildren()}. Execution
	 * still performs a physical {@code setMem} / {@code toArray} copy of the
	 * bytes the producers emit — the producer trees are visible to the
	 * optimizer but the copy semantics are unchanged.</p>
	 *
	 * @param name   Display name for this copy operation
	 * @param source Producer that yields the source memory
	 * @param target Producer that yields the target memory
	 * @param length Number of elements to copy
	 */
	public MemoryDataCopy(String name, Producer<? extends MemoryData> source,
						  Producer<? extends MemoryData> target, int length) {
		this(name,
				(Supplier<MemoryData>) () -> (MemoryData) source.get().evaluate(),
				(Supplier<MemoryData>) () -> (MemoryData) target.get().evaluate(),
				0, 0, length);
		this.sourceProducer = source;
		this.targetProducer = target;
	}

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Exposes any {@link Producer}-backed source or target (in that order)
	 * as child processes, so the optimization cascade can recurse into the
	 * producer trees that supply this copy's memory. When constructed with
	 * raw {@link Supplier}s, no producer structure is available and the
	 * children collection is empty.</p>
	 */
	@Override
	public Collection<Process<?, Runnable>> getChildren() {
		if (sourceProducer == null && targetProducer == null) {
			return Collections.emptyList();
		}

		List<Process<?, Runnable>> children = new ArrayList<>(2);
		if (sourceProducer instanceof Process) {
			children.add((Process<?, Runnable>) (Process) sourceProducer);
		}
		if (targetProducer instanceof Process) {
			children.add((Process<?, Runnable>) (Process) targetProducer);
		}
		return children;
	}

	/**
	 * Returns the compiled form of this copy, a {@link Runner}: a {@link Runnable} that is also
	 * {@link Submittable}, so that an operation list chains it rather than completing everything
	 * ahead of it on the host.
	 *
	 * <p>When the copy was created from {@link Producer}s, their {@link Evaluable}s are obtained
	 * here, once, so that compiling the producers is part of compiling the copy rather than a
	 * cost paid every time the copy runs.</p>
	 *
	 * @return the runner that performs this copy
	 */
	@Override
	public Runnable get() {
		return new Runner(sourceProducer == null ? null : sourceProducer.get(),
				targetProducer == null ? null : targetProducer.get());
	}

	/**
	 * The {@link Runnable} (and {@link Submittable}) returned by {@link MemoryDataCopy#get()}.
	 *
	 * <p>Submitted with a {@code dependsOn} completion, the copy is issued in order after it and
	 * its own completion is returned without waiting for it. The physical copy is queued through
	 * the {@link ComputeContext} that manages the target memory, exactly as a provider-to-provider
	 * {@link org.almostrealism.hardware.computations.Assignment} queues one, so under a backend
	 * that owns a command buffer the copy is encoded alongside the kernels around it. Run on its
	 * own, it submits with no dependency and waits for that completion, so a standalone copy has
	 * finished when {@link #run()} returns.</p>
	 *
	 * <p>The source and target are resolved following the convention
	 * {@link org.almostrealism.hardware.ProcessDetailsFactory} applies to kernel arguments:</p>
	 * <ul>
	 *   <li>A {@link FixedEvaluable} (the {@link io.almostrealism.relation.Provider} of a
	 *       collection, for example) is a handle whose value depends on no pending work. It is
	 *       bound at once, and only the copy itself is ordered after {@code dependsOn}.</li>
	 *   <li>A source whose evaluable is a dispatch-backed {@link StreamingEvaluable} (a compiled
	 *       kernel, for example) is requested with {@code dependsOn}, which the provider chains
	 *       into the dispatch, and delivers its result together with the completion of that
	 *       dispatch. The copy is ordered after that completion, which follows {@code dependsOn}.</li>
	 *   <li>Any other operand &mdash; a raw {@link Supplier}, or an evaluable that cannot order its
	 *       own work &mdash; may read memory the pending work is still producing, so
	 *       {@code dependsOn} is waited for before it is resolved.</li>
	 * </ul>
	 *
	 * <p>Only a copy into memory managed by a device context is left to that context to order; when
	 * a device's shared memory is also managed by the native context that delegates to it, the
	 * device context is chosen (see {@link Hardware#getComputeContext(io.almostrealism.code.Memory, boolean)}). When
	 * the target's context runs on the CPU (or no context manages the target at all), the transfer
	 * is performed by the host whichever way it is issued: queuing it behind the pending work would
	 * only move the wait onto a callback thread and add hand-offs between threads, which costs more
	 * than the wait it replaces. Such a copy is completed where it is issued, once the work it
	 * depends on has finished, and reports no outstanding completion.</p>
	 */
	private class Runner implements Runnable, Submittable, OperationInfo {
		/** The compiled source, or {@code null} when the source is a raw {@link Supplier}. */
		private final Evaluable<? extends MemoryData> sourceEvaluable;
		/** The compiled target, or {@code null} when the target is a raw {@link Supplier}. */
		private final Evaluable<? extends MemoryData> targetEvaluable;
		/** Whether the source is requested in order after the dependency rather than evaluated. */
		private final boolean chainedSource;
		/** Whether neither operand requires the dependency to complete before it is resolved. */
		private final boolean ordered;

		/**
		 * Creates a runner for this copy.
		 *
		 * @param sourceEvaluable the compiled source, or {@code null} to use the source supplier
		 * @param targetEvaluable the compiled target, or {@code null} to use the target supplier
		 */
		private Runner(Evaluable<? extends MemoryData> sourceEvaluable,
					   Evaluable<? extends MemoryData> targetEvaluable) {
			this.sourceEvaluable = sourceEvaluable;
			this.targetEvaluable = targetEvaluable;
			this.chainedSource = !(sourceEvaluable instanceof FixedEvaluable) &&
					sourceEvaluable instanceof StreamingEvaluable &&
					((StreamingEvaluable<?>) sourceEvaluable).isDispatchBacked();
			this.ordered = targetEvaluable instanceof FixedEvaluable &&
					(sourceEvaluable instanceof FixedEvaluable || chainedSource);
		}

		@Override
		public OperationMetadata getMetadata() { return metadata; }

		@Override
		public String describe() { return getMetadata().getShortDescription(); }

		/**
		 * Performs the copy and waits for it to finish.
		 */
		@Override
		public void run() {
			Semaphore done = submit(null);
			if (done != null) done.waitFor();
		}

		/**
		 * Issues the copy in order after {@code dependsOn} and returns its completion without
		 * waiting for it (see the class javadoc for how the operands are resolved).
		 *
		 * @param dependsOn the completion the copy must follow, or {@code null}
		 * @return the completion of the copy, or {@code null} if it has already finished
		 */
		@Override
		public Semaphore submit(Semaphore dependsOn) {
			Semaphore order = dependsOn;

			// Uninterruptible: resolving an operand before order has fired would read what it has not produced
			if (order != null && !ordered) {
				order.waitForUninterruptibly();
				order = null;
			}

			MemoryData target = targetEvaluable == null ?
					MemoryDataCopy.this.target.get() : targetEvaluable.evaluate();

			MemoryData source;
			if (chainedSource) {
				Delivery delivery = new Delivery();
				((StreamingEvaluable) sourceEvaluable).request(new Object[0], order, delivery);
				source = delivery.awaitValue();
				order = delivery.getCompletion();
			} else {
				source = sourceEvaluable == null ?
						MemoryDataCopy.this.source.get() : sourceEvaluable.evaluate();
			}

			if (enableVerbose) {
				log("MemoryDataCopy[" + getMetadata().getDisplayName() + "]: Copying " + source + " (" +
						sourcePosition + ") to " + target + " (" + targetPosition + ") [" + length + "]");
			}

			if (source == null) {
				throw new UnsupportedOperationException(getMetadata().getDisplayName());
			}

			ComputeContext<MemoryData> context =
					Hardware.getLocalHardware().getComputeContext(target.getMem(), true);

			// See the class javadoc: a transfer the host performs is completed here
			if ((context == null || context.isCPU()) && order != null) {
				order.waitForUninterruptibly();
				order = null;
			}

			if (context == null) {
				// Unmanaged memory (plain JVM heap, for example); host-mediated transfer
				target.setFrom(targetPosition, source, sourcePosition, length);
				return null;
			}

			return context.copy(
					new Bytes(length, source, sourcePosition),
					new Bytes(length, target, targetPosition), order);
		}
	}

	/**
	 * Receives the result a {@link StreamingEvaluable} request delivers for the source of a
	 * {@link Runner}, together with the completion of the work producing its contents.
	 *
	 * <p>A dispatch-backed request delivers on the requesting thread as soon as its dispatch has
	 * been issued, so the result is normally available when the request returns. A request that
	 * can only deliver once its work has finished (for example while output monitoring must read
	 * the result) delivers later, with no outstanding completion, and is waited for.</p>
	 *
	 * <p>Waiting for a late delivery cannot stall on work that only the waiting thread would
	 * flush: such a request is delivered from {@link Semaphore#onComplete(Runnable)}, whose
	 * callback thread calls {@link Semaphore#waitFor()} on the completion it follows, and a
	 * batching backend's completion commits its open command buffer there.</p>
	 */
	private static final class Delivery implements CompletionConsumer<MemoryData> {
		/** Released once the result has been delivered. */
		private final LatchSemaphore delivered = new LatchSemaphore(1);
		/** The delivered result. */
		private MemoryData value;
		/** The completion of the work producing the result, or {@code null} if it has finished. */
		private Semaphore completion;

		@Override
		public void accept(MemoryData value, Semaphore completion) {
			this.value = value;
			this.completion = completion;
			delivered.countDown();
		}

		/**
		 * Waits until the result has been delivered, and returns it.
		 *
		 * @return the delivered result
		 */
		private MemoryData awaitValue() {
			delivered.waitForUninterruptibly();
			return value;
		}

		/**
		 * Returns the completion delivered with the result.
		 *
		 * @return the completion of the work producing the result, or {@code null} if it has finished
		 */
		private Semaphore getCompletion() { return completion; }
	}

	@Override
	public long getOutputSize() { return length; }

	@Override
	public long getCountLong() { return length; }

	@Override
	public boolean isFixedCount() { return true; }

	@Override
	public ParallelProcess<Process<?, Runnable>, Runnable> isolate() { return this; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>When this copy exposes {@link Producer}-backed children (both source
	 * and target were provided as Producers), rebuilds the copy with the
	 * optimized children replacing those Producers. When the child count does
	 * not match the exposed structure, returns {@code this} unchanged —
	 * there is no sensible mapping from the children list back onto
	 * Supplier-only construction.</p>
	 */
	@Override
	public ParallelProcess<Process<?, Runnable>, Runnable> generate(List<Process<?, Runnable>> children) {
		if (sourceProducer != null && targetProducer != null && children.size() == 2) {
			MemoryDataCopy result = new MemoryDataCopy(
					metadata.getDisplayName(),
					(Producer<? extends MemoryData>) (Producer) children.get(0),
					(Producer<? extends MemoryData>) (Producer) children.get(1),
					length);
			result.sourcePosition = sourcePosition;
			result.targetPosition = targetPosition;
			return result;
		}
		return this;
	}

	@Override
	public String describe() {
		return metadata.getDisplayName() + " (Copy " + getOutputSize() + " values)";
	}
}
