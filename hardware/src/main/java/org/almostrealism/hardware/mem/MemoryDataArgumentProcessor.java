package org.almostrealism.hardware.mem;

import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;

import java.util.function.IntFunction;

public class MemoryDataArgumentProcessor {
	private OperationList prepare;
	private OperationList postprocess;
	private Object[] originalArguments;
	private Object[] arguments;
	private int kernelSize;

	public MemoryDataArgumentProcessor(Object[] args, MemoryProvider target, TempMemoryFactory tempFactory, int kernelSize) {
		this.prepare = new OperationList();
		this.postprocess = new OperationList();
		this.originalArguments = args;
		this.arguments = new Object[args.length];
		this.kernelSize = kernelSize;

		for (int i = 0; i < args.length; i++) {
			arguments[i] = processArgument(args[i], target, tempFactory);
		}
	}

	public OperationList getPrepare() {
		return prepare;
	}
	public OperationList getPostprocess() {
		return postprocess;
	}

	public Object[] getArguments() {
		return arguments;
	}
	public Object[] getOriginalArguments() { return originalArguments; }

	public int getKernelSize() { return kernelSize; }

	private Object processArgument(Object arg, MemoryProvider target, TempMemoryFactory tempFactory) {
		if (!(arg instanceof MemoryData)) {
			return arg;
		}

		MemoryData data = (MemoryData) arg;
		if (data.getMem().getProvider() == target) return arg;

		MemoryData tmp = tempFactory.apply(data.getMemLength(), data.getAtomicMemLength());
		if (tmp == null) {
			throw new IllegalArgumentException("Could not generate temporary memory using " + tempFactory.getClass());
		}

		prepare.add(new MemoryDataCopy("Temp Prep", data, tmp));
		postprocess.add(new MemoryDataCopy("Temp Post", tmp, data));
		return tmp;
	}

	public interface TempMemoryFactory {
		MemoryData apply(int memLength, int atomicLength);
	}
}
