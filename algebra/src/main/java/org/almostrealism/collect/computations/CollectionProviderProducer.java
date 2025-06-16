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

package org.almostrealism.collect.computations;

import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.uml.Signature;
import io.almostrealism.util.DescribableParent;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class CollectionProviderProducer<T extends Shape>
		implements CollectionProducerBase<T, Producer<T>>,
				Process<Process<?, ?>, Evaluable<? extends T>>,
				OperationInfo, Signature, DescribableParent<Process<?, ?>>,
				CollectionFeatures {
	private OperationMetadata metadata;
	private Shape value;

	public CollectionProviderProducer(Shape value) {
		this.metadata = new OperationMetadata("collection", OperationInfo.name(value),
				"Provide a collection " + value.getShape().toStringDetail());
		this.value = value;
	}

	@Override
	public OperationMetadata getMetadata() {
		return metadata;
	}

	@Override
	public Evaluable get() {
		return value instanceof PackedCollection ?
				new CollectionProvider((PackedCollection<?>) value) : new Provider(value);
	}

	@Override
	public TraversalPolicy getShape() {
		return value.getShape();
	}

	@Override
	public Producer<T> traverse(int axis) {
		return traverse(axis, (Producer) this);
	}

	@Override
	public Producer<T> reshape(TraversalPolicy shape) {
		return reshape(shape, this);
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		return this;
	}

	@Override
	public Collection<Process<?, ?>> getChildren() { return Collections.emptyList(); }

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> generate(List<Process<?, ?>> children) {
		return this;
	}

	@Override
	public String signature() {
		String shape = "|" + value.getShape().toStringDetail();

		if (value instanceof MemoryData) {
			return ((MemoryData) value).getOffset() + ":" +
				((MemoryData) value).getMemLength() + shape;
		}

		return shape;
	}

	@Override
	public String describe() { return "p(" + getShape().describe() + ")"; }

	@Override
	public String description() { return "p" + getShape().toString(); }

	@Override
	public boolean equals(Object obj) {
		if (super.equals(obj)) return true;
		if (!(obj instanceof CollectionProviderProducer)) return false;
		return ((CollectionProviderProducer) obj).value == value;
	}

	@Override
	public int hashCode() {
		return value == null ? super.hashCode() : value.hashCode();
	}
}
