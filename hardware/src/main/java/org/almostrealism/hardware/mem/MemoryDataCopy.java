package org.almostrealism.hardware.mem;

import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationWithInfo;
import io.almostrealism.compute.Process;
import org.almostrealism.hardware.MemoryData;

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
 * // Copies: source[50:150] → target[200:300]
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
 * // Prepare: Copy original → temp
 * MemoryDataCopy prepare = new MemoryDataCopy(
 *     "Temp Prep",
 *     originalData,
 *     tempData
 * );
 * prepare.get().run();
 *
 * // ... kernel execution with temp data ...
 *
 * // Postprocess: Copy temp → original
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
public class MemoryDataCopy implements Process<Process<?, Runnable>, Runnable>, OperationInfo {
	public static boolean enableVerbose = false;

	private OperationMetadata metadata;
	private Supplier<MemoryData> source;
	private Supplier<MemoryData> target;
	private int sourcePosition, targetPosition;
	private int length;

	public MemoryDataCopy(String name, MemoryData source, MemoryData target) {
		this(name, () -> source, () -> target, 0, 0, source.getMemLength());
	}

	public MemoryDataCopy(Supplier<MemoryData> source, Supplier<MemoryData> target, int length) {
		this(null, source, target, 0, 0, length);
	}

	public MemoryDataCopy(String name, Supplier<MemoryData> source, Supplier<MemoryData> target, int length) {
		this(name, source, target, 0, 0, length);
	}

	public MemoryDataCopy(Supplier<MemoryData> source, Supplier<MemoryData> target, int sourcePosition, int targetPosition, int length) {
		this(null, source, target, sourcePosition, targetPosition, length);
	}

	public MemoryDataCopy(String name, Supplier<MemoryData> source, Supplier<MemoryData> target, int sourcePosition, int targetPosition, int length) {
		this.metadata = new OperationMetadata("copy_" + length, name, "Copy " + length + " values");
		this.source = source;
		this.target = target;
		this.sourcePosition = sourcePosition;
		this.targetPosition = targetPosition;
		this.length = length;
	}

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	@Override
	public Collection<Process<?, Runnable>> getChildren() {
		return Collections.emptyList();
	}

	@Override
	public Runnable get() {
		return OperationWithInfo.RunnableWithInfo.of(getMetadata(), () -> {
			MemoryData source = this.source.get();
			MemoryData target = this.target.get();

			if (enableVerbose) {
				System.out.println("MemoryDataCopy[" + getMetadata().getDisplayName() + "]: Copying " + source + " (" +
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
	public Process<Process<?, Runnable>, Runnable> isolate() { return this; }

	@Override
	public Process<Process<?, Runnable>, Runnable> generate(List<Process<?, Runnable>> children) { return this; }

	@Override
	public String describe() {
		return metadata.getDisplayName() + " (Copy " + getOutputSize() + " values)";
	}
}
