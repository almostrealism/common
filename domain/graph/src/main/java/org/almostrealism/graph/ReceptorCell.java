/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.graph;

import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A {@link Cell} that wraps an arbitrary {@link Receptor} as a cell.
 *
 * <p>{@code ReceptorCell} allows any receptor to participate in a cell chain.
 * It acts as an adapter: all push operations are forwarded directly to the
 * wrapped receptor. The receptor can be replaced after construction via
 * {@link #setReceptor(Receptor)}, and reset is propagated to the receptor
 * if it implements {@link io.almostrealism.lifecycle.Lifecycle}.</p>
 *
 * @param <T> the type of data processed, typically {@link org.almostrealism.collect.PackedCollection}
 * @see Cell
 * @see Receptor
 * @author Michael Murray
 */
public class ReceptorCell<T> implements Cell<T> {
	/** The wrapped receptor that receives all push operations. */
	private Receptor<T> r;

	/**
	 * Creates a ReceptorCell wrapping the given receptor.
	 *
	 * @param r the receptor to wrap
	 */
	public ReceptorCell(Receptor<T> r) { this.r = r; }

	/**
	 * {@inheritDoc}
	 *
	 * @return an empty setup operation
	 */
	@Override
	public Supplier<Runnable> setup() { return new OperationList("ReceptorCell Setup"); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Forwards all push operations directly to the wrapped receptor.</p>
	 *
	 * @param protein the data producer to forward
	 * @return the receptor's push operation
	 */
	@Override
	public Supplier<Runnable> push(Producer<T> protein) { return r.push(protein); }

	/**
	 * {@inheritDoc}
	 *
	 * @param r the new downstream receptor
	 */
	@Override
	public void setReceptor(Receptor<T> r) {
		if (cellWarnings && this.r != null) {
			CollectionFeatures.console.features(ReceptorCell.class)
					.warn("Replacing receptor");
		}

		this.r = r;
	}

	/**
	 * Returns the wrapped receptor.
	 *
	 * @return the current downstream receptor
	 */
	public Receptor<T> getReceptor() { return r; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Resets the cell and propagates the reset to the receptor
	 * if it implements {@link io.almostrealism.lifecycle.Lifecycle}.</p>
	 */
	@Override
	public void reset() {
		Cell.super.reset();
		if (r instanceof Lifecycle) {
			((Lifecycle) r).reset();
		}
	}
}
