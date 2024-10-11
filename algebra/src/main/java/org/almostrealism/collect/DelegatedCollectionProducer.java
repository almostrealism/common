/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.collect;

import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;

import java.util.Collection;
import java.util.Collections;

public class DelegatedCollectionProducer<T extends PackedCollection<?>> implements
					Process<Process<?, ?>, Evaluable<? extends T>>,
					CollectionProducerBase<T, Producer<T>>,
					OperationInfo {
	protected CollectionProducer<T> op;

	public DelegatedCollectionProducer(CollectionProducer<T> op) {
		this.op = op;
	}

	@Override
	public OperationMetadata getMetadata() {
		return op instanceof OperationInfo ? ((OperationInfo) op).getMetadata() : null;
	}

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return op instanceof Process ? ((Process) op).getChildren() : Collections.emptyList();
	}

	@Override
	public Evaluable<T> get() {
		return op.get();
	}

	@Override
	public TraversalPolicy getShape() {
		return op.getShape();
	}

	@Override
	public Producer<T> traverse(int axis) { throw new UnsupportedOperationException(); }

	@Override
	public Producer<T> reshape(TraversalPolicy shape) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getOutputSize() {
		return op.getShape().getTotalSize();
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		return this;
	}
}