package org.almostrealism.hardware;

import io.almostrealism.code.MemoryProvider;

import java.util.function.IntFunction;

public class MemoryDataArgumentProcessor {
	private OperationList prepare;
	private OperationList postprocess;
	private Object[] originalArguments;
	private Object[] arguments;

	public MemoryDataArgumentProcessor(Object[] args, MemoryProvider target, TempMemoryFactory tempFactory) {
		prepare = new OperationList();
		postprocess = new OperationList();
		originalArguments = args;
		arguments = new Object[args.length];

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

		prepare.add(() -> () -> tmp.setMem(data.toArray(0, data.getMemLength())));
		postprocess.add(() -> () -> data.setMem(tmp.toArray(0, data.getMemLength())));
		return tmp;
	}

	public interface TempMemoryFactory {
		MemoryData apply(int memLength, int atomicLength);
	}
}
