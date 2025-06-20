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
