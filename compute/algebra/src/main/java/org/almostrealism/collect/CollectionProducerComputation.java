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
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Isolated;
import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.MultiTermDeltaStrategy;
import org.almostrealism.collect.computations.DefaultCollectionEvaluable;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Interface for computations that produce {@link PackedCollection} results with hardware acceleration.
 *
 * <p>
 * {@link CollectionProducerComputation} combines {@link ProducerComputation} and
 * {@link CollectionProducerParallelProcess} to provide the complete infrastructure for
 * hardware-accelerated collection operations. This interface is the foundation for all
 * computations that:
 * <ul>
 *   <li>Produce {@link PackedCollection} results</li>
 *   <li>Can be compiled to native code or GPU kernels</li>
 *   <li>Support automatic differentiation</li>
 *   <li>Enable parallel execution</li>
 * </ul>
 *
 * <h2>Key Capabilities</h2>
 * <ul>
 *   <li><b>Hardware Acceleration:</b> Automatic compilation to optimized kernels via {@link #get()}</li>
 *   <li><b>Shape Management:</b> Flexible shape handling with {@link #postProcessOutput} and {@link #shapeForLength}</li>
 *   <li><b>Process Isolation:</b> {@link IsolatedProcess} for independent execution contexts</li>
 *   <li><b>Delta Computation:</b> Automatic differentiation support via {@link #applyDeltaStrategy}</li>
 *   <li><b>Result Postprocessing:</b> Shape adjustment and wrapping via {@link #postProcessOutput}</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <pre>{@code
 * // 1. Create computation
 * CollectionProducerComputation comp = ...;
 *
 * // 2. Get evaluable (compiles to hardware)
 * Evaluable<PackedCollection> ev = comp.get();
 *
 * // 3. Execute
 * PackedCollection result = ev.evaluate();
 * }</pre>
 *
 * <h2>Configuration Flags</h2>
 * <ul>
 *   <li><b>isolationLogging:</b> Enables logging when processes are isolated (default: false,
 *       controlled by AR_ISOLATION_LOGGING env var)</li>
 *   <li><b>enableShapeTrim:</b> Avoids prepending dimensions in {@link #postProcessOutput}
 *       when enabled (default: false)</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see CollectionProducerParallelProcess
 * @see ProducerComputation
 * @see PackedCollection
 * @see DefaultCollectionEvaluable
 */
public interface CollectionProducerComputation extends
		 ProducerComputation<PackedCollection>, CollectionProducerParallelProcess {
	/** When true, logs additional information about computation isolation for debugging. */
	boolean isolationLogging = SystemUtils.isEnabled("AR_ISOLATION_LOGGING").orElse(false);

	/**
	 * When enabled, the {@link TraversalPolicy} of results from {@link #postProcessOutput(MemoryData, int)}
	 * will avoid prepending dimensions to the {@link TraversalPolicy} from {@link #getShape()}.
	 */
	// TODO  This doesn't seem to be implemented properly
	boolean enableShapeTrim = false;

	/**
	 * A collection computation is evaluated directly as a kernel.
	 *
	 * @return true
	 */
	@Override
	default boolean isDirectlyComputable() {
		return true;
	}

	/**
	 * Differentiates this computation with respect to one of its own terms using its
	 * {@link #getDeltaStrategy() multi-term delta strategy}.
	 *
	 * @param target  the variable to differentiate with respect to
	 * @return the gradient computation, or null if the strategy does not apply
	 * @see MultiTermDeltaStrategy#delta(CollectionProducerComputation, Producer)
	 */
	@Override
	default CollectionProducer applyDeltaStrategy(Producer<?> target) {
		return getDeltaStrategy().delta(this, target);
	}

	/**
	 * Returns the inputs of this computation, its destination first. These are the
	 * producers the computation was built from, including any that are not a
	 * {@link Process}, which {@link #getChildren()} would wrap.
	 *
	 * @return the inputs of this computation
	 */
	List<Producer<PackedCollection>> getInputs();

	/**
	 * Presents the {@link #getInputs() inputs} of this computation for matching, so that
	 * an input that is not a {@link Process} (which {@link #getChildren()} presents only
	 * wrapped) is still recognized as something this computation depends on.
	 *
	 * @return the inputs of this computation, empty when they are not set
	 */
	@Override
	default Stream<? extends Producer<?>> matchableInputs() {
		List<Producer<PackedCollection>> inputs = getInputs();
		return inputs == null ? Stream.empty() : inputs.stream();
	}

	/**
	 * Returns the terms of this computation, its destination first.
	 *
	 * @return the inputs of this computation
	 */
	private List<Producer<?>> getTerms() {
		return new ArrayList<>(getInputs());
	}

	/**
	 * Counts the terms of this computation that {@link Producer#refersTo refer to}
	 * the given target.
	 *
	 * @param target  the producer to look for among this computation's terms
	 * @return the number of matching terms
	 */
	default long countTerms(Producer<?> target) {
		return getTerms().stream()
				.filter(t -> t.refersTo(target))
				.count();
	}

	/**
	 * Rebuilds this computation from every term except those that
	 * {@link Producer#refersTo refer to} the given target. When a single
	 * term remains, that term is returned directly.
	 *
	 * @param target  the term to leave out
	 * @return this computation over the remaining terms, or null if it cannot be rebuilt:
	 *         several terms remain and one of them is not a {@link Process}, or the single
	 *         remaining term is neither a {@link CollectionProducer} nor a {@link Shape}
	 */
	default CollectionProducer excludeTerm(Producer<?> target) {
		return generateFromTerms(getTerms().stream()
				.filter(t -> !t.refersTo(target))
				.collect(Collectors.toList()));
	}

	@Override
	default CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		throw new UnsupportedOperationException(getClass().getName());
	}

	@Override
	default Stream<? extends Process> processChildren(Collection<? extends Process> children) {
		return children.stream()
				.filter(f -> !(f instanceof MemoryDataDestinationProducer));
	}

	/**
	 * Creates the destination {@link PackedCollection} for storing the result of this computation.
	 * Subclasses should override this to provide an appropriately shaped destination.
	 *
	 * @param len the number of elements in the destination
	 * @return a new PackedCollection to hold the computation output
	 * @throws UnsupportedOperationException if not overridden by the implementation
	 */
	default PackedCollection createDestination(int len) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Wraps the raw output memory data into a properly shaped {@link PackedCollection}.
	 * Prepends dimensions to match the expected output shape if necessary, and
	 * validates that the total size is compatible.
	 *
	 * @param output the raw output memory data from computation
	 * @param offset the element offset into the output data
	 * @return a PackedCollection view of the output with the correct shape
	 * @throws IllegalArgumentException if the output size is not compatible with the expected shape
	 */
	default PackedCollection postProcessOutput(MemoryData output, int offset) {
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
				return (PackedCollection) output;
			}
		}

		return new PackedCollection(shape, shape.getTraversalAxis(), output, offset);
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
	default Evaluable<PackedCollection> get() {
		ComputeContext<MemoryData> ctx = Hardware.getLocalHardware().getComputer().getContext(this);
		AcceleratedComputationEvaluable<PackedCollection> ev = new DefaultCollectionEvaluable<>(
				ctx, getShape(), this,
				this::createDestination, this::postProcessOutput);
		ev.load();
		return ev;
	}

	@Override
	default CollectionProducer traverse(int axis) {
		return new ReshapeProducer(axis, this);
	}

	@Override
	default CollectionProducer reshape(TraversalPolicy shape) {
		return new ReshapeProducer(shape, this);
	}

	/**
	 * Generates a new {@link CollectionProducer} from a list of children, using the
	 * structure of this computation as the template. The first child is the destination;
	 * if only one term follows it, that term is returned directly, viewed through a
	 * {@link ReshapeProducer} when it is a {@link Shape} but not a {@link CollectionProducer}.
	 * A single term that is neither has no shape to present it by, and null is returned.
	 *
	 * <p>Several terms can only be rebuilt through {@link #generate(List)}, whose children
	 * must each be a {@link Process} that is also the {@link Producer} it stands for. A
	 * term that is not a {@link Process} cannot be presented that way ({@link Process#of}
	 * would wrap it in something that is not a {@link Producer}), so the computation is
	 * not rebuilt and null is returned instead.</p>
	 *
	 * @param args  the children of the new computation, destination first
	 * @return the computation over the given terms, or null if they include a term that
	 *         is not a {@link Process} and more than one term remains, or if the single
	 *         remaining term is neither a {@link CollectionProducer} nor a {@link Shape}
	 */
	private CollectionProducer generateFromTerms(List<Producer<?>> args) {
		if (args.size() < 2) {
			throw new IllegalArgumentException("No terms remain");
		} else if (args.size() == 2) {
			Producer<?> term = args.get(1);
			if (term instanceof CollectionProducer) {
				return (CollectionProducer) term;
			} else if (term instanceof Shape) {
				return new ReshapeProducer(((Shape) term).getShape().getTraversalAxis(), (Producer) term);
			} else {
				return null;
			}
		} else if (!args.stream().allMatch(t -> t instanceof Process)) {
			return null;
		} else {
			return generate((List) args);
		}
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
	 *   <li>When kernel length is less than, or equal to, the count, returns the original shape</li>
	 *   <li>Otherwise, prepends a dimension to accommodate the length difference</li>
	 * </ul>
	 *
	 * @param len The length of the kernel execution context
	 * @return The appropriate traversal policy for the given length
	 * @see #isFixedCount()
	 * @see TraversalPolicy#forKernelLength(int, boolean, int)
	 */
	default TraversalPolicy shapeForLength(int len) {
		return getShape().forKernelLength(getCount(), isFixedCount(), len);
	}

	/**
	 * A delegating wrapper that marks a {@link CollectionProducer} as isolated,
	 * causing it to be evaluated as an independent process rather than inlined
	 * into a parent computation graph.
	 */
	class IsolatedProcess extends DelegatedCollectionProducer implements Isolated {

		/**
		 * Creates an isolated process wrapping the specified producer.
		 *
		 * @param op the collection producer to isolate
		 * @throws IllegalArgumentException if the producer's total size exceeds the maximum reservation
		 */
		public IsolatedProcess(CollectionProducer op) {
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
