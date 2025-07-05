/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.computations.DelegatedProducer;

public class DelegatedCollectionProducer<T extends PackedCollection<?>>
						extends DelegatedProducer<T>
						implements CollectionProducerBase<T, Producer<T>> {
	private boolean fixedCount;

	public DelegatedCollectionProducer(CollectionProducer<T> op) {
		this(op, true);
	}
	public DelegatedCollectionProducer(CollectionProducer<T> op, boolean directDelegate) {
		this(op, directDelegate, true);
	}
	public DelegatedCollectionProducer(CollectionProducer<T> op, boolean directDelegate, boolean fixedCount) {
		super(op, directDelegate);
		this.fixedCount = fixedCount;
	}

	@Override
	public TraversalPolicy getShape() {
		return ((CollectionProducer) op).getShape();
	}

	@Override
	public Producer<T> traverse(int axis) { throw new UnsupportedOperationException(); }

	@Override
	public Producer<T> reshape(TraversalPolicy shape) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getCountLong() {
		// TODO  This may not be necessary
		return CollectionProducerBase.super.getCountLong();
	}

	@Override
	public boolean isFixedCount() {
		return fixedCount ? super.isFixedCount() : false;
	}

	@Override
	public long getOutputSize() {
		return ((CollectionProducer) op).getShape().getTotalSize();
	}

	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) return null;

		return signature + "|" + getShape().toStringDetail();
	}
}