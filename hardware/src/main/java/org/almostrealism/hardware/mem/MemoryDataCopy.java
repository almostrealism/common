package org.almostrealism.hardware.mem;

import io.almostrealism.relation.Process;
import org.almostrealism.hardware.MemoryData;

import java.util.Collection;
import java.util.Collections;
import java.util.function.Supplier;

public class MemoryDataCopy implements Process<Process<?, Runnable>, Runnable> {
	public static boolean enableVerbose = false;

	private String name;
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
		this.name = name;
		this.source = source;
		this.target = target;
		this.sourcePosition = sourcePosition;
		this.targetPosition = targetPosition;
		this.length = length;
	}

	@Override
	public Collection<Process<?, Runnable>> getChildren() {
		return Collections.emptyList();
	}

	@Override
	public Runnable get() {
		return () -> {
			MemoryData source = this.source.get();
			MemoryData target = this.target.get();

			if (enableVerbose) {
				System.out.println("MemoryDataCopy[" + name + "]: Copying " + source + " (" +
						sourcePosition + ") to " + target + " (" + targetPosition + ") [" + length + "]");
			}

			// TODO  This can be done faster if the source and target are on the same MemoryProvider
			target.setMem(targetPosition, source.toArray(sourcePosition, length));
		};
	}
}
