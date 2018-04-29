/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.algebra;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.almostrealism.algebra.Triple;
import org.almostrealism.algebra.VectorProducer;

public abstract class VectorFutureAdapter extends ArrayList<Future<VectorProducer>>
		implements VectorProducer, Future<VectorProducer> {

	public void add(VectorProducer p) {
		addAll(convertToFutures(new VectorProducer[] { p }));
	}

	@Override
	public Vector operate(Triple in) {
		return evaluate(new Triple[] { in });
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		boolean cancelled = true;

		for (Future<VectorProducer> p : this) {
			cancelled &= p.cancel(mayInterruptIfRunning);
		}

		return cancelled;
	}

	@Override
	public boolean isCancelled() {
		for (Future<VectorProducer> p : this) {
			if (!p.isCancelled()) return false;
		}

		return true;
	}

	@Override
	public boolean isDone() {
		for (Future<VectorProducer> p : this) {
			if (!p.isDone()) return false;
		}

		return true;
	}

	@Override
	public VectorProducer get() throws InterruptedException, ExecutionException {
		return this;
	}

	@Override
	public VectorProducer get(long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TimeoutException {
		return this;
	}

	/**
	 * Delegates to the {@link VectorProducer#compact()} method for any {@link VectorProducer}s
	 * in this collection. {@link Future}s without a known {@link VectorProducer} cannot be
	 * compacted.
	 */
	@Override
	public void compact() {
		for (Future<VectorProducer> f : this) {
			if (f instanceof StaticVectorProducer) {
				((StaticVectorProducer) f).compact();
			}
		}
	}

	protected Iterable<VectorProducer> getStaticVectorProducers() {
		List<VectorProducer> l = new ArrayList<>();

		for (Future<VectorProducer> f : this) {
			if (f instanceof StaticVectorProducer) {
				l.add(((StaticVectorProducer) f).p);
			}
		}

		return l;
	}

	public static List<Future<VectorProducer>> convertToFutures(VectorProducer... producers) {
		ArrayList<Future<VectorProducer>> pr = new ArrayList<Future<VectorProducer>>();

		for (VectorProducer p : producers) {
			pr.add(new StaticVectorProducer(p));
		}

		return pr;
	}

	// TODO  When ColorProducer implements PathElement, this should be changed to
	//       implement PathElement as well
	protected static class StaticVectorProducer implements Future<VectorProducer> {
		private VectorProducer p;

		protected StaticVectorProducer(VectorProducer p) { this.p = p; }

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) { return false; }

		@Override
		public boolean isCancelled() { return false; }

		@Override
		public boolean isDone() { return false; }

		@Override
		public VectorProducer get() throws InterruptedException, ExecutionException {
			return p;
		}

		@Override
		public VectorProducer get(long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
			return get();
		}

		public void compact() { p.compact(); }

		public boolean equals(Object o) {
			if (o instanceof StaticVectorProducer == false) return false;
			return this.p == ((StaticVectorProducer) o).p;
		}

		public int hashCode() {
			return this.p.hashCode();
		}
	}
}
