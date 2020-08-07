/*
 * Copyright 2020 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.color.computations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.algebra.Triple;
import org.almostrealism.color.RGB;
import org.almostrealism.relation.TripleFunction;
import org.almostrealism.util.Producer;

public abstract class ColorFutureAdapter extends ArrayList<Future<Producer<RGB>>>
										implements ColorProducer, Future<ColorProducer>,
										TripleFunction<RGB> {

	public void add(ColorProducer p) {
		addAll(convertToFutures(new RGBProducer[] { p }));
	}
	
	@Override
	public RGB operate(Triple in) {
		return evaluate(new Triple[] { in });
	}

	@Override
	public Scope<? extends Variable> getScope(String prefix) {
		throw new RuntimeException("Not implemented");
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		boolean cancelled = true;
		
		for (Future<Producer<RGB>> p : this) {
			cancelled &= p.cancel(mayInterruptIfRunning);
		}
		
		return cancelled;
	}
	
	@Override
	public boolean isCancelled() {
		for (Future<Producer<RGB>> p : this) {
			if (!p.isCancelled()) return false;
		}
		
		return true;
	}
	
	@Override
	public boolean isDone() {
		for (Future<Producer<RGB>> p : this) {
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

	/**
	 * Delegates to the {@link ColorProducer#compact()} method for any {@link ColorProducer}s
	 * in this collection. {@link Future}s without a known {@link ColorProducer} cannot be
	 * compacted.
	 */
	@Override
	public void compact() {
		for (Future<Producer<RGB>> f : this) {
			if (f instanceof StaticColorProducer) {
				((StaticColorProducer) f).compact();
			}
		}
	}

	protected Iterable<Producer<RGB>> getStaticColorProducers() {
		List<Producer<RGB>> l = new ArrayList<>();

		for (Future<Producer<RGB>> f : this) {
			if (f instanceof StaticColorProducer) {
				l.add(((StaticColorProducer) f).p);
			}
		}

		return l;
	}

	public static List<Future<Producer<RGB>>> convertToFutures(Producer<RGB>... producers) {
		ArrayList<Future<Producer<RGB>>> pr = new ArrayList<>();
		
		for (Producer<RGB> p : producers) {
			pr.add(new StaticColorProducer(p));
		}
		
		return pr;
	}

	// TODO  When ColorProducer implements PathElement, this should be changed to
	//       implement PathElement as well
	protected static class StaticColorProducer implements Future<Producer<RGB>> {
		private Producer<RGB> p;

		protected StaticColorProducer(Producer<RGB> p) { this.p = p; }

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) { return false; }

		@Override
		public boolean isCancelled() { return false; }

		@Override
		public boolean isDone() { return false; }

		@Override
		public Producer<RGB> get() throws InterruptedException, ExecutionException {
			return p;
		}

		@Override
		public Producer<RGB> get(long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
			return get();
		}

		public void compact() { p.compact(); }

		public boolean equals(Object o) {
			if (o instanceof StaticColorProducer == false) return false;
			return this.p == ((StaticColorProducer) o).p;
		}

		public int hashCode() {
			return this.p.hashCode();
		}
	}
}
