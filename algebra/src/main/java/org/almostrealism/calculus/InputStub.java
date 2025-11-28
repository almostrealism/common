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

package org.almostrealism.calculus;

import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.Collection;
import java.util.List;

/**
 * A wrapper that isolates a producer for delta (gradient) computation in automatic differentiation.
 *
 * <p>
 * {@link InputStub} is used internally by {@link DeltaFeatures} to create isolated computational
 * graphs during gradient computation. When computing partial derivatives using the chain rule,
 * it's necessary to treat certain inputs as independent variables while preserving their shape
 * and metadata.
 * </p>
 *
 * <h2>Purpose</h2>
 * <p>
 * During automatic differentiation, the chain rule requires computing df/dx when f depends on x
 * indirectly through intermediate computations. {@link InputStub} creates a stand-in for x that:
 * <ul>
 *   <li>Preserves the shape and type information of the original producer</li>
 *   <li>Breaks the computational graph connection to enable isolated delta computation</li>
 *   <li>Maintains identity through unique metadata IDs for matching during gradient assembly</li>
 *   <li>Supports reshape and traverse operations to maintain compatibility</li>
 * </ul>
 *
 * <h2>Usage in Chain Rule</h2>
 * <pre>{@code
 * // For h(x) = f(g(x)), compute dh/dx = (df/dg) . (dg/dx)
 * Producer<T> g = ...; // intermediate computation
 * Producer<T> h = f(g); // composite computation
 *
 * // Create stub for g to compute df/dg in isolation
 * InputStub<T> gStub = new InputStub<>(g);
 * Producer<T> dfdg = f(gStub).delta(gStub);  // df/dg
 *
 * // Then multiply by dg/dx
 * Producer<T> dgdx = g.delta(x);
 * Producer<T> dhdx = matmul(dfdg, dgdx);  // Full gradient
 * }</pre>
 *
 * <h2>Matching Behavior</h2>
 * <p>
 * {@link InputStub} instances match based on their unique metadata ID, enabling the gradient
 * system to correctly identify which stub corresponds to which original input during chain
 * rule application.
 * </p>
 *
 * @param <T>  the packed collection type
 * @author  Michael Murray
 * @see DeltaFeatures#generateIsolatedDelta
 * @see DeltaFeatures#replaceInput
 */
// TODO  This should probably extend DelegatedProducer
public class InputStub<T extends PackedCollection> implements CollectionProducer<T>,
														ParallelProcess<Process<?, ?>, Evaluable<? extends T>>,
														Algebraic, OperationInfo {
	private final OperationMetadata metadata;
	private final Producer<T> producer;

	/**
	 * Creates a new input stub wrapping the specified producer.
	 *
	 * @param producer  the producer to wrap as a stub
	 */
	public InputStub(Producer<T> producer) {
		this(new OperationMetadata("stub", "InputStub"), producer);
	}

	/**
	 * Creates a new input stub with the specified metadata and producer.
	 *
	 * @param metadata  the operation metadata for this stub
	 * @param producer  the producer to wrap as a stub
	 */
	protected InputStub(OperationMetadata metadata, Producer<T> producer) {
		this.metadata = new OperationMetadata(metadata);
		this.producer = producer;
		prepareMetadata();
	}

	protected String extendDescription(String description, boolean brief) {
		if (brief) {
			return "stub(" + description + ")";
		} else {
			return getClass().getSimpleName() + "(" + description + ")";
		}
	}

	protected void prepareMetadata() {
		if (producer instanceof OperationInfo) {
			OperationMetadata child = ((OperationInfo) producer).getMetadata();
			this.metadata.setDisplayName(
					extendDescription(child.getDisplayName(), true));
			this.metadata.setShortDescription(
					extendDescription(child.getShortDescription(), false));
			this.metadata.setChildren(List.of(child));
		}
	}

	/**
	 * Returns the operation metadata for this stub, used for matching and tracking.
	 *
	 * @return the operation metadata
	 */
	public OperationMetadata getMetadata() { return metadata; }

	@Override
	public long getParallelism() {
		return ParallelProcess.parallelism(producer);
	}

	@Override
	public long getCountLong() {
		return CollectionProducer.super.getCountLong();
	}

	@Override
	public boolean isFixedCount() {
		return Countable.isFixedCount(producer);
	}

	@Override
	public TraversalPolicy getShape() {
		return shape(producer);
	}

	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		return CollectionProducer.super.containsIndex(index);
	}

	/**
	 * Returns a reshaped version of this stub, preserving the stub wrapper.
	 *
	 * @param shape  the new shape
	 * @return a new stub wrapping the reshaped producer
	 * @throws UnsupportedOperationException if the wrapped producer is not a CollectionProducer
	 */
	@Override
	public CollectionProducer<T> reshape(TraversalPolicy shape) {
		if (producer instanceof CollectionProducer) {
			return new InputStub<>(((CollectionProducer<T>) producer).reshape(shape));
		}

		throw new UnsupportedOperationException();
	}

	/**
	 * Returns a traversed version of this stub along the specified axis, preserving the stub wrapper.
	 *
	 * @param axis  the axis to traverse
	 * @return a new stub wrapping the traversed producer
	 * @throws UnsupportedOperationException if the wrapped producer is not a CollectionProducer
	 */
	@Override
	public CollectionProducer<T> traverse(int axis) {
		if (producer instanceof CollectionProducer) {
			return new InputStub<>(((CollectionProducer<T>) producer).traverse(axis));
		}

		throw new UnsupportedOperationException();
	}

	@Override
	public ParallelProcess<Process<?, ?>, Evaluable<? extends T>> optimize(ProcessContext ctx) {
		return ParallelProcess.super.optimize(ctx);
	}

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return List.of((Process<?, ?>) producer);
	}

	@Override
	public ParallelProcess<Process<?, ?>, Evaluable<? extends T>> generate(List<Process<?, ?>> children) {
		return new InputStub<>(metadata, (Producer) children.iterator().next());
	}

	@Override
	public Evaluable<T> get() {
		return producer.get();
	}

	/**
	 * Checks if this stub matches another algebraic object.
	 *
	 * <p>
	 * Matching behavior:
	 * <ul>
	 *   <li>If the other object is an InputStub with the same metadata ID -> matches</li>
	 *   <li>Otherwise, delegates to the wrapped producer's matching logic</li>
	 * </ul>
	 * This enables the gradient system to correctly identify corresponding stubs
	 * during chain rule application.
	 * </p>
	 *
	 * @param other  the other algebraic object to compare
	 * @param <A>  the algebraic type
	 * @return true if this stub matches the other object
	 */
	@Override
	public <A extends Algebraic> boolean matches(A other) {
		if (other instanceof InputStub<?> && getMetadata().getId() == ((InputStub<?>) other).getMetadata().getId()) {
			return true;
		} else if (producer instanceof Algebraic) {
			return ((Algebraic) producer).matches(other);
		}

		return false;
	}

	@Override
	public String describe() {
		return "<stub> " + getShape().toStringDetail();
	}
}
