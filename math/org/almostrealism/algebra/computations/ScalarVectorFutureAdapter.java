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

package org.almostrealism.algebra.computations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.util.Producer;

public abstract class ScalarVectorFutureAdapter extends ArrayList<Future<Producer<Vector>>>
		implements Producer<Scalar>, Future<Producer<Scalar>> {

	public void add(Producer<Scalar> p) {
		addAll(convertToFutures(new Producer[] { p }));
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		boolean cancelled = true;

		for (Future<Producer<Vector>> p : this) {
			cancelled &= p.cancel(mayInterruptIfRunning);
		}

		return cancelled;
	}

	@Override
	public boolean isCancelled() {
		for (Future<Producer<Vector>> p : this) {
			if (!p.isCancelled()) return false;
		}

		return true;
	}

	@Override
	public boolean isDone() {
		for (Future<Producer<Vector>> p : this) {
			if (!p.isDone()) return false;
		}

		return true;
	}

	@Override
	public Producer<Scalar> get() throws InterruptedException, ExecutionException {
		return this;
	}

	@Override
	public Producer<Scalar> get(long timeout, TimeUnit unit)
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
		for (Future<Producer<Vector>> f : this) {
			if (f instanceof VectorFutureAdapter.StaticVectorProducer) {
				((VectorFutureAdapter.StaticVectorProducer) f).compact();
			}
		}
	}

	protected Iterable<Producer<Vector>> getStaticVectorProducers() {
		List<Producer<Vector>> l = new ArrayList<>();

		for (Future<Producer<Vector>> f : this) {
			if (f instanceof VectorFutureAdapter.StaticVectorProducer) {
				l.add(((VectorFutureAdapter.StaticVectorProducer) f).p);
			}
		}

		return l;
	}

	public static List<Future<Producer<Vector>>> convertToFutures(Producer<Vector>... producers) {
		ArrayList<Future<Producer<Vector>>> pr = new ArrayList<>();

		for (Producer<Vector> p : producers) {
			pr.add(new VectorFutureAdapter.StaticVectorProducer(p));
		}

		return pr;
	}

	// TODO  When ColorProducer implements PathElement, this should be changed to
	//       implement PathElement as well
	protected static class StaticScalarProducer implements Future<Producer<Scalar>> {
		private Producer<Scalar> p;

		protected StaticScalarProducer(Producer<Scalar> p) { this.p = p; }

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) { return false; }

		@Override
		public boolean isCancelled() { return false; }

		@Override
		public boolean isDone() { return false; }

		@Override
		public Producer<Scalar> get() throws InterruptedException, ExecutionException {
			return p;
		}

		@Override
		public Producer<Scalar> get(long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
			return get();
		}

		public void compact() { p.compact(); }

		public boolean equals(Object o) {
			if (o instanceof StaticScalarProducer == false) return false;
			return this.p == ((StaticScalarProducer) o).p;
		}

		public int hashCode() {
			return this.p.hashCode();
		}
	}
}
