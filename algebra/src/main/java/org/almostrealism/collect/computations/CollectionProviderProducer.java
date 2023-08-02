/*
 * Copyright 2023 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.collect.CollectionFeatures;

import java.util.Collection;
import java.util.Collections;

public class CollectionProviderProducer<T extends Shape>
		implements CollectionProducerBase<T, Producer<T>>,
				Process<Process<?, ?>, Evaluable<? extends T>>,
				CollectionFeatures {
	private Shape value;

	public CollectionProviderProducer(Shape value) {
		this.value = value;
	}

	@Override
	public Evaluable get() {
		return new Provider(value);
	}

	@Override
	public TraversalPolicy getShape() {
		return value.getShape();
	}

	@Override
	public Producer reshape(TraversalPolicy shape) {
		return reshape(shape, this);
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		return this;
	}

	@Override
	public Collection<Process<?, ?>> getChildren() { return Collections.emptyList(); }
}
