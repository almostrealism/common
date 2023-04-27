package org.almostrealism.hardware;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Named;
import org.jocl.CLException;

import java.util.stream.Stream;

public class DestinationEvaluable<T extends MemoryBank> implements Evaluable<T> {
	private Evaluable<T> operation;
	private MemoryBank destination;

	public DestinationEvaluable(Evaluable<T> operation, MemoryBank destination) {
		this.operation = operation;
		this.destination = destination;
	}

	@Override
	public T evaluate(Object... args) {
		if (operation instanceof AcceleratedOperation && ((AcceleratedOperation) operation).isKernel()) {
			((AcceleratedOperation) operation).kernelOperate(destination, Stream.of(args).map(arg -> (MemoryData) arg).toArray(MemoryData[]::new));
		} else {
			String name = operation instanceof Named ? ((Named) operation).getName() : OperationAdapter.operationName(null, getClass(), "function");
			if (KernelizedOperation.enableKernelLog) System.out.println("DestinationEvaluable: Evaluating " + name + " kernel...");

			boolean enableLog = false;

			for (int i = 0; i < destination.getCount(); i++) {
				T r = null;

				try {
					final int fi = i;
					Object o[] = Stream.of(args)
							.map(arg -> ((MemoryBank) arg).get(fi)).toArray();

					r = operation.evaluate(o);
					if (r == null) r = replaceNull(o);

					destination.set(i, r);
				} catch (UnsupportedOperationException e) {
					throw new HardwareException("i = " + i + " of " + destination.getCount() + ", r = " + r, e);
				} catch (CLException e) {
					throw new HardwareException("i = " + i + " of " + destination.getCount() + ", r = " + r, e);
				}

				if (enableLog && (i + 1) % 100 == 0) System.out.println((i + 1) + " kernel results collected");
			}
		}

		return (T) destination;
	}

	public T replaceNull(Object[] o) {
		if (operation instanceof KernelizedEvaluable) {
			return (T) ((KernelizedEvaluable) operation).replaceNull(o);
		} else {
			throw new NullPointerException();
		}
	}
}
