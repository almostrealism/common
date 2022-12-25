package org.almostrealism.hardware.mem;

import org.almostrealism.hardware.MemoryData;

import java.util.function.Supplier;

public class MemoryDataCopy implements Supplier<Runnable> {
	private String name;
	private Supplier<MemoryData> source;
	private Supplier<MemoryData> target;
	private int sourcePosition, targetPosition;
	private int length;

	public MemoryDataCopy(String name, MemoryData source, MemoryData target) {
		this(name, () -> source, () -> target, 0, 0, source.getMemLength());
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
	public Runnable get() {
		return () -> {
			MemoryData source = this.source.get();
			MemoryData target = this.target.get();

			// System.out.println("MemoryDataCopy[" + name + "]: Copying " + source + " (" + sourcePosition + ") to " + target + " (" + targetPosition + ")");
			target.setMem(targetPosition, source.toArray(sourcePosition, length));
		};
	}
}
