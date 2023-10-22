package org.almostrealism.hardware.mem;

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Semaphore;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public class AcceleratedProcessDetails {
	private boolean enableAggregation = true;
	public static int aggregationThreshold = 1024 * 1024;

	private OperationList prepare;
	private OperationList postprocess;
	private Object[] originalArguments;
	private Object[] arguments;
	private int kernelSize;

	private Semaphore semaphore;

	public AcceleratedProcessDetails(Object[] args, MemoryProvider target, TempMemoryFactory tempFactory, int kernelSize) {
		this.prepare = new OperationList();
		this.postprocess = new OperationList();
		this.originalArguments = args;
		this.arguments = new Object[args.length];
		this.kernelSize = kernelSize;

		arguments = processArguments(args, target, tempFactory);
	}

	public OperationList getPrepare() {
		return prepare;
	}
	public OperationList getPostprocess() {
		return postprocess;
	}

	public boolean isEmpty() {
		return prepare.isEmpty() && postprocess.isEmpty();
	}

	public Semaphore getSemaphore() { return semaphore; }
	public void setSemaphore(Semaphore semaphore) { this.semaphore = semaphore; }

	public Object[] getArguments() { return arguments; }
	public Object[] getOriginalArguments() { return originalArguments; }

	public int getKernelSize() { return kernelSize; }

	private Object[] processArguments(Object args[], MemoryProvider target, TempMemoryFactory tempFactory) {
		if (!enableAggregation) return args;

		Map<MemoryData, Replacement> replacements = new HashMap<>();

		Object result[] = new Object[args.length];

		i: for (int i = 0; i < args.length; i++) {
			Object arg = args[i];

			if (!(arg instanceof MemoryData)) {
				result[i] = arg;
				continue i;
			}

			MemoryData data = (MemoryData) arg;
			if (data.getMem() == null)
				throw new IllegalArgumentException();
			if (data.getMem().getProvider() == target || data.getMemLength() > aggregationThreshold) {
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
}
