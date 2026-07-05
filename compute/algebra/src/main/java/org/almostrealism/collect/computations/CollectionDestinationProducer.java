/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.mem.MemoryDataDestination;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;

import java.util.function.IntFunction;

/**
 * Self-contained destination producer for collection computations: it carries the
 * output {@link TraversalPolicy} and creates destinations itself, rather than
 * delegating creation back into the computation that constructed it.
 *
 * <p>The destination for a dispatch of {@code len} kernel instances is determined
 * entirely by three values — the carried output shape, that shape's count, and the
 * owning computation's fixed-count semantics — combined through
 * {@link CollectionProducerComputation#shapeForLength(TraversalPolicy, int, boolean, int)}.
 * The first two are captured here at construction. The fixed-count value is read from
 * the owner as plain data at creation time rather than captured, because subclasses of
 * {@link CollectionProducerComputationBase} may override
 * {@link Countable#isFixedCount()} with state that is not yet assigned when this
 * producer is constructed (the base constructor builds it before subclass
 * initialization completes). No creation behavior is delegated to the owner.</p>
 *
 * <p>There is deliberately no support for reusing a previously created destination:
 * the historical adjust-existing-bank path never received a prior bank through this
 * producer, so creation is unconditional.</p>
 *
 * <p>As a {@link ProducerArgumentReference} for slot 0, this producer also binds a
 * caller-supplied destination: dispatch argument arrays reserve their leading slot for
 * the destination, so when that slot is populated the destination is bound from it like
 * any other referenced argument, and when it is null (or absent, for operations with no
 * result) the destination is created here instead.</p>
 *
 * @see MemoryDataDestination
 * @see CollectionProducerComputationBase
 */
public class CollectionDestinationProducer extends MemoryDataDestinationProducer<PackedCollection>
		implements ProducerArgumentReference {

	/** The output shape of the computation this producer creates destinations for. */
	private final TraversalPolicy shape;

	/** Fixed-count semantics source; consulted as plain data, never for creation behavior. */
	private final Countable owner;

	/**
	 * Creates a destination producer for a computation with the given output shape.
	 *
	 * @param shape the computation's output {@link TraversalPolicy}
	 * @param owner the computation, consulted only for {@link Countable#isFixedCount()}
	 */
	public CollectionDestinationProducer(TraversalPolicy shape, Countable owner) {
		super(owner);
		this.shape = shape;
		this.owner = owner;
	}

	/**
	 * Creates a destination sized for the given number of kernel instances, using
	 * {@link CollectionProducerComputation#shapeForLength(TraversalPolicy, int, boolean, int)}
	 * with the carried shape and the owner's fixed-count semantics.
	 *
	 * @param len the kernel size of the dispatch the destination will receive
	 * @return a freshly allocated destination, or {@code null} when {@code len} is not positive
	 */
	public MemoryBank<PackedCollection> createDestination(int len) {
		if (len <= 0) return null;

		TraversalPolicy destinationShape = CollectionProducerComputation.shapeForLength(
				shape, shape.getCount(), owner.isFixedCount(), len);
		return new PackedCollection(destinationShape);
	}

	@Override
	public int getReferencedArgumentIndex() { return 0; }

	@Override
	public IntFunction<MemoryBank<PackedCollection>> getDestinationFactory() {
		return this::createDestination;
	}

	@Override
	public Evaluable<PackedCollection> get() {
		return new MemoryDataDestination<>(this::createDestination);
	}
}
