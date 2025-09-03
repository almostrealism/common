package io.almostrealism.streams;

import java.util.function.Consumer;

public abstract class StreamingEvaluableBase<T> implements StreamingEvaluable<T> {
	private Consumer<T> downstream;

	protected Consumer<T> getDownstream() { return downstream; }

	@Override
	public void setDownstream(Consumer<T> consumer) {
		this.downstream = consumer;
	}
}
