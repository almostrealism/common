/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A minimal {@link Cell} implementation that forwards input unchanged to its receptor.
 *
 * <p>{@code PassThroughCell} acts as a transparent relay in the computation graph.
 * It performs no transformation on the input data and simply forwards it to the
 * configured receptor. This is useful as a placeholder or connector in cell chains.</p>
 *
 * @param <T> the type of data passed through
 * @see Cell
 * @author Michael Murray
 */
public class PassThroughCell<T> implements Cell<T> {
	/** The downstream receptor to receive forwarded data. */
	private Receptor<T> r;

	/**
	 * {@inheritDoc}
	 *
	 * @return an empty setup operation
	 */
	@Override
	public Supplier<Runnable> setup() { return new OperationList("PassThroughCell Setup"); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Forwards the input unchanged to the downstream receptor.</p>
	 *
	 * @param protein the data producer to forward
	 * @return the receptor's push operation
	 */
	@Override
	public Supplier<Runnable> push(Producer<T> protein) { return r.push(protein); }

	/**
	 * {@inheritDoc}
	 *
	 * @param r the downstream receptor
	 */
	@Override
	public void setReceptor(Receptor<T> r) {
		if (cellWarnings && this.r != null) {
			CollectionFeatures.console.features(PassThroughCell.class)
					.warn("Replacing receptor");
		}

		this.r = r;
	}
}
