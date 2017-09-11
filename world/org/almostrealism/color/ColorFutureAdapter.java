package org.almostrealism.color;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.almostrealism.algebra.Triple;

public abstract class ColorFutureAdapter extends ArrayList<Future<ColorProducer>>
										implements ColorProducer, Future<ColorProducer> {

	public void add(ColorProducer p) {
		addAll(convertToFutures(new ColorProducer[] { p }));
	}
	
	@Override
	public RGB operate(Triple in) {
		return evaluate(new Triple[] { in });
	}
	
	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		boolean cancelled = true;
		
		for (Future<ColorProducer> p : this) {
			cancelled &= p.cancel(mayInterruptIfRunning);
		}
		
		return cancelled;
	}
	
	@Override
	public boolean isCancelled() {
		for (Future<ColorProducer> p : this) {
			if (!p.isCancelled()) return false;
		}
		
		return true;
	}
	
	@Override
	public boolean isDone() {
		for (Future<ColorProducer> p : this) {
			if (!p.isDone()) return false;
		}
		
		return true;
	}
	
	@Override
	public ColorProducer get() throws InterruptedException, ExecutionException {
		return this;
	}
	
	@Override
	public ColorProducer get(long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TimeoutException {
		return this;
	}
	
	public static List<Future<ColorProducer>> convertToFutures(ColorProducer... producers) {
		ArrayList<Future<ColorProducer>> pr = new ArrayList<Future<ColorProducer>>();
		
		for (ColorProducer p : producers) {
			pr.add(new Future<ColorProducer>() {
				@Override
				public boolean cancel(boolean mayInterruptIfRunning) { return false; }

				@Override
				public boolean isCancelled() { return false; }

				@Override
				public boolean isDone() { return false; }

				@Override
				public ColorProducer get() throws InterruptedException, ExecutionException {
					return p;
				}

				@Override
				public ColorProducer get(long timeout, TimeUnit unit)
						throws InterruptedException, ExecutionException, TimeoutException {
					return get();
				}
			});
		}
		
		return pr;
	}
}
