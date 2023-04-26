package org.almostrealism.hardware;

import io.almostrealism.relation.Evaluable;

import java.util.stream.Stream;

public class DestinationEvaluable<T extends MemoryBank> implements Evaluable<T> {
	private AcceleratedOperation<T> operation;
	private T destination;

	public DestinationEvaluable(AcceleratedOperation<T> operation, T destination) {
		this.operation = operation;
		this.destination = destination;
	}

	@Override
	public T evaluate(Object... args) {
		operation.kernelOperate(destination, Stream.of(args).map(arg -> (MemoryData) arg).toArray(MemoryData[]::new));
		return destination;
	}
}
