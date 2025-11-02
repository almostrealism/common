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

import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Parent;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.computations.DefaultCollectionEvaluable;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataAdapter;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface CollectionProducerComputation<T extends PackedCollection<?>> extends
		 ProducerComputation<T>, CollectionProducerParallelProcess<T> {
	boolean isolationLogging = SystemUtils.isEnabled("AR_ISOLATION_LOGGING").orElse(false);

	/**
	 * When enabled, the {@link TraversalPolicy} of results from {@link #postProcessOutput(MemoryData, int)}
	 * will avoid prepending dimensions to the {@link TraversalPolicy} from {@link #getShape()}.
	 */
	// TODO  This doesn't seem to be implemented properly
	boolean enableShapeTrim = false;

	@Override
	default <V extends Shape<?>> CollectionProducer<V> applyDeltaStrategy(CollectionProducer<V> producer,
																		  Producer<?> target) {
		Collection<Producer<?>> terms;

		if (producer instanceof Parent) {
			terms = (Collection) ((Parent<?>) producer).getChildren().stream()
					.map(t -> (Producer) t)
					.collect(Collectors.toList());
		} else {
			return CollectionProducerParallelProcess.super.applyDeltaStrategy(producer, target);
		}

		return (CollectionProducer) deltaStrategyProcessor(producer.getDeltaStrategy(),
				producerFactory(this), shape(producer), target).apply(terms);
	}

	@Override
	default CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		throw new UnsupportedOperationException(getClass().getName());
	}

	@Override
	default Stream<? extends Process> processChildren(Collection<? extends Process> children) {
		return children.stream()
				.filter(f -> !(f instanceof MemoryDataDestinationProducer));
	}

	default T createDestination(int len) {
		throw new UnsupportedOperationException();
	}

	default T postProcessOutput(MemoryData output, int offset) {
		TraversalPolicy shape = getShape();

		s: if (output instanceof Shape) {
			TraversalPolicy outputShape = ((Shape) output).getShape();

			while (shape.getDimensions() < outputShape.getDimensions()) {
				int dim = outputShape.getDimensions() - shape.getDimensions();

				if (enableShapeTrim && shape.getDimensions() == outputShape.getDimensions() - 1 && dim == 1) {
					break s;
				} else {
					shape = shape.prependDimension(outputShape.length(dim - 1));
				}
			}

			if (shape.getTotalSize() != outputShape.getTotalSize()) {
				throw new IllegalArgumentException("Output is not compatible with expected shape");
			}

			if (offset == 0 && shape.equals(((Shape) output).getShape())) {
				return (T) output;
			}
		}

		return (T) new PackedCollection(shape, shape.getTraversalAxis(), output, offset);
	}

	/**
	 * Creates an {@link Evaluable} instance for this collection producer computation.
	 * This method instantiates a {@link DefaultCollectionEvaluable} configured with
	 * the computation's context, shape, and processing functions, providing a ready-to-use
	 * evaluable for executing the computation with hardware acceleration.
	 * 
	 * <p>The method performs the following steps:</p>
	 * <ol>
	 *   <li>Obtains a compute context from the local hardware for this computation</li>
	 *   <li>Creates a new {@link DefaultCollectionEvaluable} with:
	 *       <ul>
	 *         <li>The obtained compute context</li>
	 *         <li>This computation's shape via {@link #getShape()}</li>
	 *         <li>This computation instance itself</li>
	 *         <li>Method references to {@link #createDestination(int)} and {@link #postProcessOutput(MemoryData, int)}</li>
	 *       </ul>
	 *   </li>
	 *   <li>Compiles the evaluable for execution</li>
	 *   <li>Returns the compiled evaluable</li>
	 * </ol>
	 * 
	 * <p>This is the standard factory method for creating evaluable instances in the
	 * AlmostRealism collection computation framework. The resulting evaluable can be
	 * used multiple times to evaluate the computation with different inputs.</p>
	 * 
	 * @return a compiled {@link Evaluable} instance ready for computation execution
	 * 
	 * @throws RuntimeException if hardware context cannot be obtained or compilation fails
	 * 
	 * @see DefaultCollectionEvaluable
	 * @see Hardware#getLocalHardware()
	 * @see AcceleratedComputationEvaluable#compile()
	 */
	@Override
	default Evaluable<T> get() {
		ComputeContext<MemoryData> ctx = Hardware.getLocalHardware().getComputer().getContext(this);
		AcceleratedComputationEvaluable<T> ev = new DefaultCollectionEvaluable<>(
				ctx, getShape(), this,
				this::createDestination, this::postProcessOutput);
		ev.load();
		return ev;
	}

	@Override
	default CollectionProducer<T> traverse(int axis) {
		return new ReshapeProducer(axis, this);
	}

	@Override
	default CollectionProducer<T> reshape(TraversalPolicy shape) {
		return new ReshapeProducer(shape, this);
	}

	default <T extends MemoryDataAdapter> T collect(Function<TraversalPolicy, T> factory) {
		PackedCollection c = get().evaluate();
		T data = factory.apply(c.getShape());
		data.setDelegate(c, 0);
		return data;
	}

	static <T extends PackedCollection<?>> Function<List<Producer<?>>, CollectionProducer<T>>
				producerFactory(CollectionProducerComputation<T> original) {
		return args -> {
			List<Producer<?>> terms = new ArrayList<>();
			args.stream().skip(1).forEach(terms::add);

			if (terms.isEmpty()) {
				throw new IllegalArgumentException();
			} else if (terms.size() == 1) {
				return (CollectionProducer<T>) terms.get(0);
			} else {
				return original.generate((List) args.stream()
						.map(t -> (Process) t).collect(Collectors.toList()));
			}
		};
	}

	static <T extends Shape<?>> boolean isIsolationPermitted(CollectionProducer<T> op) {
		return Process.isolationPermitted(op) &&
				op.getShape().getTotalSizeLong() <= MemoryProvider.MAX_RESERVATION;
	}

	/**
	 * Determines the appropriate {@link TraversalPolicy} for a given kernel length.
	 * This will be the shape of the computation result.
	 * This method handles the complex logic of adjusting shapes based on whether
	 * the computation has a fixed count and the relationship between the kernel
	 * length and the expected output count.
	 *
	 * <p>The shape calculation follows these rules:</p>
	 * <ul>
	 *   <li>For fixed-count computations, returns the original shape</li>
	 *   <li>When kernel length equals target count, returns the original shape</li>
	 *   <li>Otherwise, prepends a dimension to accommodate the length difference</li>
	 * </ul>
	 *
	 * @param len The length of the kernel execution context
	 * @return The appropriate traversal policy for the given length
	 * @see #isFixedCount()
	 * @see TraversalPolicy#prependDimension(int)
	 */
	static TraversalPolicy shapeForLength(TraversalPolicy computationShape,
										  int computationCount,
										  boolean fixedCount, int len) {
		TraversalPolicy shape;

		if (fixedCount) {
			shape = computationShape;
		} else {
			int count = len / computationCount;

			// When kernel length is less than, or identical to the output count, an
			// assumption is made that the intended shape is the original shape.
			// This is a bit of a hack, but it's by far the simplest solution
			// available
			if (count == 0 || len == computationCount) {
				// It is not necessary to prepend a (usually) unnecessary dimension
				shape = computationShape;
			} else {
				shape = computationShape.prependDimension(count);
			}
		}

		return shape;
	}

	class IsolatedProcess<T extends PackedCollection<?>> extends DelegatedCollectionProducer<T> {

		public IsolatedProcess(CollectionProducer<T> op) {
			super(op);

			if (isolationLogging)
				Computation.console.features(this)
						.log("Isolating " + OperationInfo.nameWithId(op) + " " + op.getShape().toStringDetail());

			if (op.getShape().getTotalSizeLong() > MemoryProvider.MAX_RESERVATION) {
				throw new IllegalArgumentException("Cannot isolate a process with a total size greater than " +
						MemoryProvider.MAX_RESERVATION);
			}
		}

		@Override
		public boolean isConstant() { return op.isConstant(); }
	}
}
