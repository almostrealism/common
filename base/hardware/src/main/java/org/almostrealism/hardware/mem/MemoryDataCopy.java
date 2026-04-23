package org.almostrealism.hardware.mem;

import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationWithInfo;
import io.almostrealism.relation.Producer;
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
 * <p>Current implementation uses {@code toArray()} for copying, which may be inefficient for
 * same-provider transfers:</p>
 * <pre>
 * // Current: Always goes through Java array
 * double[] data = source.toArray(sourcePos, length);
 * target.setMem(targetPos, data);
 *
 * // TODO: Direct provider copy for same-provider transfers
 * if (source.getMem().getProvider() == target.getMem().getProvider()) {
 *     provider.directCopy(source, target, length);  // Faster
 * }
 * </pre>
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
 * @see MemoryReplacementManager
 * @see Process
 */
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

	@Override
	public Runnable get() {
		return OperationWithInfo.RunnableWithInfo.of(getMetadata(), () -> {
			MemoryData source = this.source.get();
			MemoryData target = this.target.get();

			if (enableVerbose) {
				log("MemoryDataCopy[" + getMetadata().getDisplayName() + "]: Copying " + source + " (" +
						sourcePosition + ") to " + target + " (" + targetPosition + ") [" + length + "]");
			}

			if (source == null) {
				throw new UnsupportedOperationException(getMetadata().getDisplayName());
			}

			// TODO  This can be done faster if the source and target are on the same MemoryProvider
			target.setMem(targetPosition, source.toArray(sourcePosition, length));
		});
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
