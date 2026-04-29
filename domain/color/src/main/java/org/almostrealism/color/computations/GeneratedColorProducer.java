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
import io.almostrealism.code.Computation;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Generated;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;

import java.util.Collection;
import java.util.Collections;

/**
 * A {@link CollectionProducerComputation} that pairs a generator object with a lazily-evaluated
 * color {@link Producer}, implementing the {@link Generated} pattern.
 *
 * <p>Shading classes such as {@link org.almostrealism.color.PointLight} and
 * {@link org.almostrealism.color.HighlightShader} wrap their computed color producers in a
 * {@code GeneratedColorProducer} so that the originating object (the generator) can be retrieved
 * later for reflective inspection or caching.</p>
 *
 * <p>Use the static factory {@link #fromProducer(Object, Producer)} to construct instances.</p>
 *
 * @param <T> the type of the generator object (e.g., a shader or light source)
 * @see Generated
 * @author Michael Murray
 */
public class GeneratedColorProducer<T> implements Generated<T, Producer<PackedCollection>>, CollectionProducerComputation {
	/** The underlying producer that evaluates the color output. */
	private Producer<PackedCollection> p;

	/** The object that created or owns this color producer (e.g., a shader or light). */
	private T generator;

	/**
	 * Constructs a {@link GeneratedColorProducer} with a generator but no producer yet.
	 *
	 * @param generator the object that will generate the color producer
	 */
	protected GeneratedColorProducer(T generator) {
		this.generator = generator;
	}

	/**
	 * Constructs a {@link GeneratedColorProducer} with both a generator and a producer.
	 *
	 * @param generator the object that created this producer
	 * @param p         the color producer to wrap
	 */
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

	/** Returns the generator object that created this color producer. */
	@Override
	public T getGenerator() { return generator; }

	/**
	 * Returns the underlying color {@link Producer} wrapped by this instance.
	 *
	 * @return the wrapped producer
	 */
	public Producer<PackedCollection> getProducer() {
		return p;
	}

	/**
	 * Returns the generated color {@link Producer}, which is the same as {@link #getProducer()}.
	 *
	 * @return the wrapped producer
	 */
	@Override
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
	public CollectionProducer reshape(TraversalPolicy shape) {
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

	/**
	 * Creates a {@link GeneratedColorProducer} that pairs the given generator with the given producer.
	 *
	 * @param <T>       the generator type
	 * @param generator the object that created or owns the producer
	 * @param p         the color producer to wrap
	 * @return a new {@link GeneratedColorProducer}
	 */
	public static <T> GeneratedColorProducer<T> fromProducer(T generator, Producer<? extends PackedCollection> p) {
		return new GeneratedColorProducer(generator, p);
	}
}
