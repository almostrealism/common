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

package org.almostrealism.color.computations;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.Computation;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Generated;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;

import java.util.Collection;
import java.util.Collections;

public class GeneratedColorProducer<T> implements Generated<T, Producer<PackedCollection>>, CollectionProducerComputation<PackedCollection> {
	private Producer<PackedCollection> p;
	private T generator;

	protected GeneratedColorProducer(T generator) {
		this.generator = generator;
	}

	protected GeneratedColorProducer(T generator, Producer<PackedCollection> p) {
		this.generator = generator;
		this.p = p;
	}

	@Override
	public OperationMetadata getMetadata() {
		if (p instanceof OperationInfo) {
			return ((OperationInfo) p).getMetadata();
		}

		return null;
	}

	public T getGenerator() { return generator; }

	public Producer<PackedCollection> getProducer() {
		return p;
	}

	public Producer<PackedCollection> getGenerated() { return p; }

	@Override
	public TraversalPolicy getShape() {
		return ((Shape) getGenerated()).getShape();
	}

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return p instanceof Process ? ((Process) p).getChildren() : Collections.emptyList();
	}

	@Override
	public long getCountLong() { return getShape().getCountLong(); }

	@Override
	public CollectionProducer<PackedCollection> reshape(TraversalPolicy shape) {
		return (CollectionProducer) ((Shape) getGenerated()).reshape(shape);
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		if (p instanceof Computation) {
			((Computation) p).prepareArguments(map);
		}
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		if (p instanceof Computation) {
			((Computation) p).prepareScope(manager, context);
		}
	}

	@Override
	public Scope<PackedCollection> getScope(KernelStructureContext context) { return ((Computation) p).getScope(context); }

	@Override
	public Evaluable<PackedCollection> get() { return p.get(); }

	public static <T> GeneratedColorProducer<T> fromProducer(T generator, Producer<? extends PackedCollection> p) {
		return new GeneratedColorProducer(generator, p);
	}
}
