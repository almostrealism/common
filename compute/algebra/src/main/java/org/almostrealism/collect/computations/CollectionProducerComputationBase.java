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

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.IndexSet;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.calculus.InputStub;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Abstract base class for collection-based computation operations in the Almost Realism framework.
 * This class provides the fundamental infrastructure for computations that operate on packed collections
 * of data with support for hardware acceleration, memory management, and multi-dimensional traversal.
 * 
 * <p>This class serves as the cornerstone for implementing efficient, parallelizable computations on
 * multi-dimensional data structures. It integrates with the hardware acceleration system to provide
 * high-performance computation capabilities while maintaining a clean abstraction for collection operations.</p>
 * 
 * <h2>Key Features:</h2>
 * <ul>
 *   <li><strong>Hardware Acceleration:</strong> Leverages GPU and CPU parallelization</li>
 *   <li><strong>Memory Management:</strong> Intelligent destination buffer management and reuse</li>
 *   <li><strong>Shape Management:</strong> Flexible handling of multi-dimensional data structures via 
 *       {@link TraversalPolicy}</li>
 *   <li><strong>Delta Computation:</strong> The {@link #delta(Producer, ReduceAxis, Producer) delta}
 *       of every computation, through protected extension points whose order is described
 *       by {@link #deltaDependent}</li>
 *   <li><strong>Lifecycle Management:</strong> Comprehensive resource management and cleanup</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Example of a concrete implementation for element-wise addition
 * public class AdditionComputation extends CollectionProducerComputationBase {
 *     public AdditionComputation(TraversalPolicy shape,
 *                               Producer<PackedCollection> a,
 *                               Producer<PackedCollection> b) {
 *         super("addition", shape, a, b);
 *     }
 *
 *     @Override
 *     public Scope<PackedCollection> getScope(KernelStructureContext context) {
 *         // Implementation of the computation kernel
 *         // ...
 *     }
 * }
 * 
 * // Usage
 * TraversalPolicy shape = new TraversalPolicy(100, 50); // 100x50 matrix
 * Producer<PackedCollection> sourceA = ...; // First input producer
 * Producer<PackedCollection> sourceB = ...; // Second input producer
 * AdditionComputation computation = new AdditionComputation(shape, sourceA, sourceB);
 * PackedCollection result = computation.get().evaluate();
 * }</pre>
 * 
 * <h2>Thread Safety:</h2>
 * <p>This class is <strong>not thread-safe</strong>. Instances should not be shared across threads
 * without external synchronization. However, once a computation is compiled and obtained via {@link #get()},
 * the resulting {@link Evaluable} can be safely used concurrently.</p>
 * 
 * <h2>Memory Considerations:</h2>
 * <p>This class manages memory allocation automatically through the {@link #adjustDestination(MemoryBank, Integer)}
 * method. Large computations may hit memory limits defined by {@link MemoryProvider#MAX_RESERVATION}.
 * Applications should call {@link #destroy()} when computations are no longer needed to free resources.</p>
 *
 * 
 * @author Michael Murray
 * @since 0.69
 * 
 * @see CollectionProducerComputation
 * @see TraversalPolicy  
 * @see PackedCollection
 */
public abstract class CollectionProducerComputationBase
		extends ProducerComputationBase<PackedCollection, PackedCollection>
												implements CollectionProducerComputation, IndexSet,
															MemoryDataComputation<PackedCollection>,
															HardwareFeatures {
	/**
	 * Global flag to enable logging of destination buffer operations.
	 * When enabled, provides detailed logging of memory allocation and shape adjustments
	 * during computation execution. Primarily used for debugging memory management issues.
	 * 
	 * @see #destinationLog(Runnable)
	 * @see CollectionProducerComputation#shapeForLength(int)
	 */
	public static boolean enableDestinationLogging = false;

	/** The human-readable name of this computation, used for debugging and profiling. */
	private String name;
	
	/** The traversal policy defining the multi-dimensional shape and access pattern of the output. */
	private TraversalPolicy shape;
	
	/** Optional post-processor function to transform raw memory data into the final output type. */
	private BiFunction<MemoryData, Integer, PackedCollection> postprocessor;
	
	/** Optional short-circuit evaluable that bypasses normal computation for optimization. */
	private Evaluable<PackedCollection> shortCircuit;
	
	/** List of dependent lifecycle objects that need to be managed alongside this computation. */
	private List<ScopeLifecycle> dependentLifecycles;

	/** Cached hardware-accelerated evaluable instance for this computation. */
	private HardwareEvaluable<PackedCollection> evaluable;

	/** Custom description function for generating human-readable computation descriptions. */
	private Function<List<String>, String> description;

	/**
	 * Protected default constructor for subclasses that need to perform custom initialization
	 * before calling the main constructor.
	 */
	protected CollectionProducerComputationBase() {
	}

	/**
	 * Creates a new {@link CollectionProducerComputation} with the specified parameters.
	 * This is the primary constructor used by concrete implementations.
	 * 
	 * <p>The output shape must have a positive total size, and all arguments must be non-null.
	 * The first argument position is reserved for the destination buffer, which is automatically
	 * managed by this base class.</p>
	 *
	 * <p>Any {@link io.almostrealism.collect.TraversalOrdering} on the output shape is removed:
	 * a computation produces a dense output, with one element per logical index. An ordered
	 * collection is a correct <em>input</em> to a computation, which reads it through its
	 * ordering, but producing an ordered (compact or sparse) output would mean writing only the
	 * elements the ordering gives a position, which is a scatter or segmented reduction rather
	 * than the element-wise evaluation every computation performs. Such outputs belong to
	 * dedicated sparse computations, not to this base class.</p>
	 *
	 * @param name A human-readable name for this computation, used in debugging and profiling.
	 *             May be null, in which case a default name will be generated.
	 * @param outputShape The {@link TraversalPolicy} defining the multi-dimensional shape of the output.
	 *                   Must have a total size greater than zero.
	 * @param arguments Variable argument list of {@link Supplier}s that provide the input {@link Evaluable}s.
	 *                 Each supplier must be non-null and produce a valid evaluable when called.
	 * 
	 * @throws IllegalArgumentException if the output shape has a total size of zero or less
	 * @throws NullPointerException if any argument supplier is null
	 * 
	 * @see #validateArgs(Producer[])
	 * @see TraversalPolicy#getTotalSizeLong()
	 */
	@SafeVarargs
	public CollectionProducerComputationBase(String name, TraversalPolicy outputShape,
											 Producer<PackedCollection>... arguments) {
		this();

		if (outputShape.getTotalSizeLong() <= 0) {
			throw new IllegalArgumentException("Output shape must have a total size greater than 0");
		}

		this.name = name;
		this.shape = outputShape.withOrder(null);

		List<Producer<PackedCollection>> inputs = new ArrayList<>();
		inputs.add(new MemoryDataDestinationProducer<>(this, this::adjustDestination));
		inputs.addAll(List.of(arguments));
		setInputs(inputs);

		init();
	}

	/**
	 * Returns the name of this computation.
	 * If no explicit name was provided during construction, returns the default name
	 * from the parent class.
	 * 
	 * @return The name of this computation, never null
	 */
	@Override
	public String getName() {
		return name == null ? super.getName() : name;
	}

	/**
	 * Prepares the operation metadata by adding shape information to the base metadata. The
	 * signature is the one the base metadata defers to {@link #signature()}, so it is computed
	 * only if it is requested.
	 *
	 * @param metadata The base metadata to enhance
	 * @return Enhanced metadata including shape information
	 */
	@Override
	protected OperationMetadata prepareMetadata(OperationMetadata metadata) {
		return super.prepareMetadata(metadata).withShape(getShape());
	}

	/**
	 * Retrieves the input arguments as a {@link List} of {@link ArrayVariable} for {@link Double}s.
	 * This is used internally for {@link io.almostrealism.scope.Scope} preparation and argument handling.
	 * 
	 * @return {@link List} of input argument variables
	 */
	protected List<ArrayVariable<Double>> getInputArguments() {
		return (List) getInputs().stream().map(this::getArgumentForInput).collect(Collectors.toList());
	}

	/**
	 * Computes the delta of this computation, as {@link CollectionProducer#delta(Producer,
	 * ReduceAxis, Producer)} describes. The cases determined by identity alone are left to
	 * that implementation; every other case is the {@link #deltaDependent dependent delta},
	 * which is where the extension points of a computation's derivative begin. This method
	 * is not overridden.
	 */
	@Override
	public CollectionProducer delta(Producer<?> target, ReduceAxis axis, Producer<PackedCollection> weight) {
		if (cannotMatch(target) || refersTo(target)) {
			return CollectionProducerComputation.super.delta(target, axis, weight);
		}

		return deltaDependent(target, axis, weight);
	}

	/**
	 * Indicates whether this computation may be differentiated through an intermediate
	 * input using the multivariate chain rule, which the {@link #deltaDependent dependent
	 * delta} then applies using the {@link #deltaPartial partial delta} with respect to
	 * that input. Subclasses whose expression can be differentiated with respect to a
	 * stubbed input return true.
	 *
	 * @return true if chain rule differentiation is supported (default: false)
	 */
	protected boolean isChainRuleSupported() {
		return false;
	}

	/**
	 * Returns the full Jacobian of this computation with respect to a target it depends on,
	 * by whatever means this computation has of its own. This is the last of the
	 * {@link #deltaDependent dependent delta}'s strategies, used for the Jacobian only
	 * when it cannot be formed from the Jacobians of the inputs, either
	 * {@link #deltaElement element by element} or by the chain rule (when
	 * {@link #isChainRuleSupported()}), and for a reduced delta only when neither of
	 * those applies, in which case the result is contracted. Computations that can
	 * differentiate their own expression override this; the default has no such means.
	 *
	 * @param target the producer with respect to which to differentiate
	 * @return the Jacobian, in the shape of this computation followed by that of the target
	 * @throws UnsupportedOperationException if this computation has no means of its own
	 */
	protected CollectionProducer deltaMatrix(Producer<?> target) {
		throw new UnsupportedOperationException("No Jacobian of " + describe());
	}

	/**
	 * Differentiates this computation with respect to a target that it may depend on and
	 * is not, for the full Jacobian or either {@link ReduceAxis reduced} delta. This is the
	 * first of the extension points of a computation's derivative, and the one to override
	 * when a computation has a rule of its own that should take precedence over every
	 * strategy below; an override defers to this implementation for any case its rule
	 * does not cover. A computation that only needs one of those strategies overrides the
	 * extension point for it instead:
	 * <ul>
	 *   <li>{@link #deltaElement(int)}, when this computation is element-wise in an input.
	 *       Every input that may depend on the target is then differentiated element by
	 *       element, for any number of such inputs and for all three axes.</li>
	 *   <li>{@link #isChainRuleSupported()}, when this computation can be differentiated
	 *       with respect to one of its inputs held as a variable, which is its
	 *       {@link #deltaPartial partial delta}. The multivariate chain rule then applies
	 *       when exactly one input depends on the target,
	 *       <pre>
	 * For h(x) = f(g(x)), compute: dh/dx = (df/dg) . (dg/dx)
	 *       </pre>
	 *       and when that input is the target itself, which is not a variable that this
	 *       computation's expression could be differentiated by, the delta is that
	 *       partial delta.</li>
	 *   <li>{@link #deltaMatrix(Producer)}, when this computation can produce its own
	 *       Jacobian with respect to the target, used when neither of the above applies.</li>
	 * </ul>
	 *
	 * <p>The reduced deltas pass the reduction on to the inputs, so that neither this
	 * computation's Jacobian with respect to the target nor that of any input {@code h} is
	 * formed:</p>
	 * <pre>
	 * OUT:  gᵀ d(this)/dx = Σ_h (gᵀ d(this)/dh) dh/dx    -  Σ_h h.delta(x, OUT, this.delta(h, OUT, g))
	 * IN:   d(this)/dx t  = Σ_h d(this)/dh (dh/dx t)      -  Σ_h this.delta(h, IN, h.delta(x, IN, t))
	 * </pre>
	 * <p>When neither the element-wise form nor the chain rule applies to a reduced delta,
	 * the full Jacobian is {@link #jacobianContraction contracted}.</p>
	 *
	 * @param target the producer with respect to which to differentiate
	 * @param axis   the axis of the Jacobian to sum away
	 * @param weight the weight of each entry along that axis, or null to weight every
	 *               entry by one
	 * @return the delta, in the shape of the axes that remain
	 */
	protected CollectionProducer deltaDependent(Producer<?> target, ReduceAxis axis, Producer<PackedCollection> weight) {
		if (axis == ReduceAxis.NONE) {
			CollectionProducer jacobian = isFixedCount() ? deltaElementMatrix(target) : null;
			if (jacobian == null) jacobian = deltaInput(target, axis, weight);
			if (jacobian == null) jacobian = deltaChainMatrix(target);
			return jacobian == null ? deltaMatrix(target) : jacobian;
		}

		CollectionProducer elementwise = deltaElementReduced(target, axis, weight);
		if (elementwise != null) return elementwise;

		CollectionProducer input = deltaInput(target, axis, weight);
		if (input != null) return input;

		if (!isFixedCount() || !isChainRuleSupported()) {
			return jacobianContraction(this, target, axis, weight);
		}

		Optional<Producer<PackedCollection>> match = matchInput(target);
		if (match == null || match.isEmpty() || match.get().refersTo(target) ||
				!(match.get() instanceof Shape)) {
			return jacobianContraction(this, target, axis, weight);
		}

		Producer<PackedCollection> in = match.get();

		if (axis == ReduceAxis.OUT) {
			return c(in).delta(target, axis, deltaPartial(in, axis, weight));
		}

		return deltaPartial(in, axis, c(in).delta(target, axis, weight));
	}

	/**
	 * Computes the delta with respect to a target that is itself the one input of this
	 * computation that may depend on it, when that target is a computation rather than a
	 * variable. This computation's expression does not present such a target as a variable
	 * to differentiate by, so the delta is the {@link #deltaPartial partial delta} with
	 * respect to that input, which is the whole of it because the input's own delta with
	 * respect to the target is the identity.
	 *
	 * @param target the producer with respect to which to differentiate
	 * @param axis   the axis of the Jacobian to sum away
	 * @param weight the weight of each entry along that axis, or null to weight every
	 *               entry by one
	 * @return the delta, or null when the target is not such an input
	 */
	private CollectionProducer deltaInput(Producer<?> target, ReduceAxis axis, Producer<PackedCollection> weight) {
		if (!isChainRuleSupported() || target.getMatchRoot().isMatchRoot()) return null;

		Optional<Producer<PackedCollection>> match = matchInput(target);
		if (match == null || match.isEmpty() || !match.get().refersTo(target) ||
				!(match.get() instanceof Shape)) {
			return null;
		}

		Producer<PackedCollection> in = match.get();
		if (shape(in).equals(shape(target))) {
			return deltaPartial(in, axis, weight);
		} else if (axis == ReduceAxis.IN && weight != null) {
			weight = c(weight).reshape(shape(in));
		}

		return deltaPartial(in, axis, weight).reshape(axis.resultShape(getShape(), shape(target)));
	}

	/**
	 * Forms the full Jacobian of this computation by the chain rule through the one input
	 * that may depend on the target, as {@link #deltaDependent} describes, when that is
	 * possible.
	 *
	 * @param target the producer with respect to which to differentiate
	 * @return the Jacobian, or null when the chain rule does not apply
	 */
	private CollectionProducer deltaChainMatrix(Producer<?> target) {
		if (!isChainRuleSupported()) return null;

		if (!isFixedCount()) {
			warn("Cannot compute partial delta for variable Producer");
			return null;
		}

		TraversalPolicy shape = getShape();
		TraversalPolicy targetShape = shape(target);
		Optional<Producer<PackedCollection>> match = matchInput(target);

		if (match == null) {
			return null;
		} else if (match.isEmpty()) {
			return zeros(shape.append(targetShape));
		}

		Producer<?> in = match.get();
		if (in.refersTo(target) || !(in instanceof CollectionProducer)) return null;

		CollectionProducer f = deltaPartial(in, ReduceAxis.NONE, null);
		CollectionProducer g = ((CollectionProducer) in).delta(target);

		int finalLength = shape.getTotalSize();
		int outLength = shape(in).getTotalSize();
		int inLength = targetShape.getTotalSize();

		return matmul(reshape(shape(finalLength, outLength), f),
				reshape(shape(outLength, inLength), g))
				.reshape(shape.append(targetShape));
	}

	/**
	 * Computes the delta of this computation with respect to one of its inputs, holding
	 * every other input constant: the partial derivative that the chain rule of the
	 * {@link #deltaDependent dependent delta} multiplies by the delta of that input. The
	 * input is replaced by an {@link InputStub} in a regenerated copy of this computation,
	 * and that copy is differentiated with respect to the stub, which is why the chain
	 * rule requires {@link #isChainRuleSupported()}. This is a tool for the extension
	 * points of the derivative, not one of them, and is not overridden.
	 *
	 * @param input  the input of this computation to differentiate with respect to
	 * @param axis   the axis of the Jacobian to sum away
	 * @param weight the weight of each entry along that axis, or null to weight every
	 *               entry by one
	 * @return the partial delta, in the shape of the axes that remain
	 */
	protected CollectionProducer deltaPartial(Producer<?> input, ReduceAxis axis, Producer<PackedCollection> weight) {
		InputStub stub = new InputStub((Producer) input);
		return replaceInput(input, stub).delta(stub, axis, weight);
	}

	/**
	 * Returns the derivative of each element of this computation with respect to the
	 * element of one of its inputs that it is computed from, when this computation is
	 * element-wise in that input: output {@code i} depends on that input only through its
	 * element {@code i}, or, for an input of a single element, through that element. The
	 * result has the shape of this computation (or broadcasts to it) and is a computation
	 * of the inputs' values, so the derivative of this computation with respect to the
	 * input is the diagonal matrix it describes. Computations that are not element-wise in
	 * the input return null, which is the default.
	 *
	 * <p>This is the cheapest of the extension points of a computation's derivative (see
	 * {@link #deltaDependent}): when every input that may depend on the target has an
	 * element delta, the Jacobian and both reduced deltas are formed from them, for any
	 * number of such inputs, without any rule of this computation's own.</p>
	 *
	 * @param index the position of the input among {@link #getInputs()}, where the
	 *              destination is at position zero
	 * @return the element-wise partial derivative, or null if this computation is not
	 *         element-wise in that input
	 */
	protected CollectionProducer deltaElement(int index) {
		return null;
	}

	/**
	 * Computes the reduced delta from the {@link #deltaElement element-wise partial}
	 * derivatives of this computation, summing over every input that may depend on the
	 * target. An input that appears more than once contributes the sum of its partials. For
	 * an input {@code h} with partial {@code p}:
	 * <pre>
	 * OUT:  h.delta(x, OUT, g ⊙ p)     (summed over the outputs when h is a single element)
	 * IN:   p ⊙ h.delta(x, IN, t)
	 * </pre>
	 *
	 * @param target the producer with respect to which to differentiate
	 * @param axis   the axis of the Jacobian to sum away
	 * @param weight the weight of each entry along that axis, or null to weight every
	 *               entry by one
	 * @return the reduced delta, or null if this computation is not element-wise in every
	 *         input that may depend on the target
	 */
	private CollectionProducer deltaElementReduced(Producer<?> target, ReduceAxis axis, Producer<PackedCollection> weight) {
		Map<Producer<PackedCollection>, CollectionProducer> partials = deltaElements(target);
		if (partials == null) return null;

		TraversalPolicy shape = getShape();
		List<Producer<?>> terms = new ArrayList<>();

		for (Map.Entry<Producer<PackedCollection>, CollectionProducer> entry : partials.entrySet()) {
			CollectionProducer in = c(entry.getKey());
			CollectionProducer partial = entry.getValue();
			boolean scalar = in.getShape().getTotalSizeLong() != shape.getTotalSizeLong();

			if (axis == ReduceAxis.OUT) {
				CollectionProducer local = multiply(
						weight == null ? constant(shape, 1.0) : c(weight).reshape(shape), partial);
				if (scalar) local = local.reshape(shape.getTotalSize()).sum();
				terms.add(in.delta(target, axis, local.reshape(in.getShape())));
			} else {
				CollectionProducer tangent = in.delta(target, axis, weight);
				terms.add(multiply(scalar ? tangent : tangent.reshape(shape), partial).reshape(shape));
			}
		}

		return terms.size() == 1 ? (CollectionProducer) terms.get(0) : add(terms);
	}

	/**
	 * Computes the full Jacobian of this computation from its
	 * {@link #deltaElement element-wise partial} derivatives: each input {@code h}
	 * contributes its own Jacobian with every row scaled by the partial of the matching
	 * output, or, when {@code h} is a single element, the outer product of the partial
	 * with that element's Jacobian.
	 *
	 * @param target the producer with respect to which to differentiate
	 * @return the Jacobian, in the shape of this computation followed by that of the
	 *         target, or null if this computation is not element-wise in every input that
	 *         may depend on the target
	 */
	private CollectionProducer deltaElementMatrix(Producer<?> target) {
		Map<Producer<PackedCollection>, CollectionProducer> partials = deltaElements(target);
		if (partials == null) return null;

		TraversalPolicy shape = getShape();
		int outSize = shape.getTotalSize();
		int targetSize = shape(target).getTotalSize();
		List<Producer<?>> terms = new ArrayList<>();

		for (Map.Entry<Producer<PackedCollection>, CollectionProducer> entry : partials.entrySet()) {
			CollectionProducer in = c(entry.getKey());
			CollectionProducer jacobian = in.delta(target);

			if (in.getShape().getTotalSize() == outSize) {
				jacobian = jacobian.reshape(outSize, targetSize);
			} else {
				jacobian = jacobian.reshape(targetSize).repeat(outSize).reshape(outSize, targetSize);
			}

			CollectionProducer partial = multiply(constant(shape, 1.0), entry.getValue())
					.reshape(outSize).traverse(1).repeat(targetSize).reshape(outSize, targetSize);
			terms.add(multiply(jacobian, partial));
		}

		CollectionProducer jacobian = terms.size() == 1 ? (CollectionProducer) terms.get(0) : add(terms);
		return jacobian.reshape(shape.append(shape(target)));
	}

	/**
	 * Returns the {@link #deltaElement element-wise partial} derivative of this
	 * computation with respect to each input that may depend on the target, the partials
	 * of an input that appears more than once summed.
	 *
	 * @param target the producer with respect to which to differentiate
	 * @return the partial derivative of each such input, or null if there are none or this
	 *         computation is not element-wise in every one of them
	 */
	private Map<Producer<PackedCollection>, CollectionProducer> deltaElements(Producer<?> target) {
		long size = getShape().getTotalSizeLong();
		List<Producer<PackedCollection>> inputs = getInputs();
		Map<Producer<PackedCollection>, CollectionProducer> partials = new LinkedHashMap<>();

		for (int i = 1; i < inputs.size(); i++) {
			Producer<PackedCollection> in = inputs.get(i);
			if (in.cannotMatch(target)) continue;
			if (!(in instanceof Shape)) return null;

			long inSize = ((Shape<?>) in).getShape().getTotalSizeLong();
			if (inSize != size && inSize != 1) return null;

			CollectionProducer partial = deltaElement(i);
			if (partial == null) return null;

			partials.merge(in, partial, (p, q) -> add(p, q));
		}

		return partials.isEmpty() ? null : partials;
	}

	/**
	 * Returns a copy of this computation with one of its inputs replaced by another
	 * producer of the same shape, leaving every other input in place.
	 *
	 * @param input       the input to replace
	 * @param replacement the producer to use in its place
	 * @return the regenerated computation
	 * @throws IllegalArgumentException if {@code input} is not an input of this computation
	 */
	public CollectionProducer replaceInput(Producer<?> input, Producer<?> replacement) {
		List<Producer<PackedCollection>> current = getInputs();
		if (current.stream().skip(1).noneMatch(input::equals)) {
			throw new IllegalArgumentException(input + " is not an input of " + describe());
		}

		List<Process<?, ?>> inputs = new ArrayList<>();
		inputs.add(null);
		current.stream().skip(1)
				.map(in -> input.equals(in) ? (Process<?, ?>) replacement : (Process<?, ?>) in)
				.forEach(inputs::add);

		return generate(inputs);
	}

	/**
	 * Adds a dependent lifecycle object to this computation.
	 * Dependent lifecycles are managed alongside this computation's lifecycle,
	 * ensuring proper resource allocation and cleanup.
	 * 
	 * @param lifecycle The lifecycle object to add as a dependency
	 * @return This computation instance for method chaining
	 * @see #addAllDependentLifecycles(Iterable)
	 * @see #getDependentLifecycles()
	 */
	public CollectionProducerComputationBase addDependentLifecycle(ScopeLifecycle lifecycle) {
		if (dependentLifecycles == null) {
			dependentLifecycles = new ArrayList<>();
		}

		dependentLifecycles.add(lifecycle);
		return this;
	}

	/**
	 * Adds multiple dependent lifecycle objects to this computation.
	 * Convenient method for adding several lifecycle dependencies at once.
	 * 
	 * @param lifecycles An iterable collection of lifecycle objects to add
	 * @return This computation instance for method chaining
	 * @see #addDependentLifecycle(ScopeLifecycle)
	 */
	public CollectionProducerComputationBase addAllDependentLifecycles(Iterable<ScopeLifecycle> lifecycles) {
		lifecycles.forEach(this::addDependentLifecycle);
		return this;
	}

	/**
	 * Returns the list of dependent lifecycle objects for this computation.
	 * These objects are managed alongside this computation's lifecycle.
	 * 
	 * @return Unmodifiable list of dependent lifecycles, empty if none exist
	 */
	public List<ScopeLifecycle> getDependentLifecycles() {
		return dependentLifecycles == null ? Collections.emptyList() : dependentLifecycles;
	}

	/**
	 * Prepares the scope for kernel compilation by setting up input management
	 * and configuring argument ordering hints.
	 * 
	 * <p>This method ensures that dependent lifecycles are properly prepared
	 * and sets a sort hint to prioritize the result argument in generated kernels.</p>
	 * 
	 * @param manager The scope input manager for handling argument preparation
	 * @param context The kernel structure context providing compilation information
	 */
	@Override
	public void prepareScope(ArgumentProvider manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		if (dependentLifecycles != null) ScopeLifecycle.prepareScope(dependentLifecycles.stream(), manager, context);
	}


	/**
	 * Resets the computation arguments and invalidates cached evaluables.
	 * This method should be called when argument values change to ensure
	 * that subsequent evaluations use the updated arguments.
	 * 
	 * <p>This method also resets all dependent lifecycles and marks the
	 * cached evaluable as outdated, forcing recompilation if needed.</p>
	 */
	@Override
	public void resetArguments() {
		super.resetArguments();

		if (dependentLifecycles != null)
			ScopeLifecycle.resetArguments(dependentLifecycles.stream());

		// this.evaluable = null;
	}

	/**
	 * Adjusts the destination buffer to match the required length and shape.
	 * This method handles intelligent memory management by reusing existing buffers
	 * when possible and allocating new ones only when necessary.
	 * 
	 * <p>The adjustment logic:</p>
	 * <ul>
	 *   <li>If length is null or zero, destroys the existing buffer and returns null</li>
	 *   <li>If existing buffer is too small, destroys it and creates a new one</li>
	 *   <li>If existing buffer has the exact shape, returns it unchanged</li>
	 *   <li>Otherwise, returns a range view of the existing buffer</li>
	 * </ul>
	 * 
	 * @param existing The existing memory bank, may be null
	 * @param len The required length for the destination buffer
	 * @return Adjusted memory bank, or null if length is invalid
	 * @throws IllegalArgumentException if len is null
	 * @see CollectionProducerComputation#shapeForLength(int)
	 */
	protected MemoryBank<PackedCollection> adjustDestination(MemoryBank<?> existing, Integer len) {
		if (len == null) {
			throw new IllegalArgumentException();
		} else if (len <= 0) {
			if (existing != null) {
				existing.getRootDelegate().destroy();
			}

			return null;
		}

		TraversalPolicy shape = shapeForLength(len);

		if (!(existing instanceof PackedCollection) || existing.getMem() == null ||
				((PackedCollection) existing).getShape().getTotalSize() < shape.getTotalSize()) {
			if (existing != null) existing.getRootDelegate().destroy();
			return new PackedCollection(shape);
		}

		if (((PackedCollection) existing).getShape().equals(shape))
			return (MemoryBank<PackedCollection>) existing;

		return ((PackedCollection) existing).range(shape);
	}

	/**
	 * Creates a destination buffer of the specified length.
	 * This method delegates to the underlying destination provider to create
	 * an appropriately sized output buffer.
	 * 
	 * @param len The required length of the destination buffer
	 * @return A new destination buffer of the specified length
	 */
	@Override
	public PackedCollection createDestination(int len) {
		return (PackedCollection) getDestination().createDestination(len);
	}

	/**
	 * Returns the traversal policy that defines the multi-dimensional shape
	 * and access pattern of the output data.
	 * 
	 * @return The traversal policy for this computation's output
	 */
	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	/**
	 * Returns the memory length required for this computation.
	 * This represents the number of elements operated on by one kernel thread.
	 * 
	 * @return The memory length in elements per kernel thread
	 */
	@Override
	public int getMemLength() {
		return getShape().getSize();
	}

	/**
	 * Returns the number of kernel threads that will be used for this computation.
	 * This is derived from the traversal policy and represents the
	 * total count of kernel threads that will be executed.
	 * 
	 * @return The number of kernel threads that will be used, or 0 if shape is null
	 */
	@Override
	public long getCountLong() {
		return Optional.ofNullable(getShape())
				.map(TraversalPolicy::getCountLong)
				.orElse(0L);
	}

	/**
	 * Determines whether this computation has a fixed execution count.
	 * A computation has a fixed count only if both the shape's count is fixed
	 * and all parent constraints indicate a fixed count.
	 *
	 * @return true if the count is fixed, false otherwise
	 */
	@Override
	public boolean isFixedCount() {
		return getShape().isFixedCount() && super.isFixedCount();
	}

	/**
	 * Returns the maximum parallelism level for this computation.
	 * If the output size exceeds the maximum reservation limit, returns -1
	 * to indicate that independent compilation is not possible.
	 * 
	 * @return The parallelism level, or -1 if the computation is too large
	 * @see MemoryProvider#MAX_RESERVATION
	 */
	@Override
	public long getParallelism() {
		if (getOutputSize() > MemoryProvider.MAX_RESERVATION) {
			// This cannot even be independently compiled, so it is
			// false to claim that it has any level of parallelism
			return -1;
		}

		return super.getParallelism();
	}

	/**
	 * Determines whether the computation contains the specified index.
	 * Delegates to the parent interface's default implementation.
	 * 
	 * @param index The index expression to check
	 * @return Boolean expression indicating whether the index is contained
	 */
	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		return CollectionProducerComputation.super.containsIndex(index);
	}

	/**
	 * Returns the total output size of this computation.
	 * This represents the total number of elements in the output collection.
	 * 
	 * @return The total output size
	 */
	@Override
	public long getOutputSize() {
		return getShape().getTotalSizeLong();
	}

	/**
	 * Creates an isolated process for this computation if possible.
	 * If the total size exceeds the maximum reservation limit, returns this
	 * computation unchanged and logs a warning.
	 * 
	 * @return An isolated process, or this computation if isolation is not possible
	 * @see MemoryProvider#MAX_RESERVATION
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends PackedCollection>> isolate() {
		if (getShape().getTotalSizeLong() > MemoryProvider.MAX_RESERVATION) {
			warn("Cannot isolate a process with a total size greater than " + MemoryProvider.MAX_RESERVATION);
			return this;
		}

		return new CollectionProducerComputation.IsolatedProcess(this);
	}

	/**
	 * Returns the post-processor function for transforming raw output data.
	 * The post-processor can be used to apply final transformations to the
	 * computation result before returning it to the caller.
	 * 
	 * @return The post-processor function, or null if none is set
	 * @see #setPostprocessor(BiFunction)
	 */
	public BiFunction<MemoryData, Integer, PackedCollection> getPostprocessor() {
		return postprocessor;
	}

	/**
	 * Sets the post-processor function for transforming raw output data.
	 * This function will be applied to the raw memory data to produce the final output.
	 * 
	 * @param postprocessor The post-processor function to set
	 * @return This computation instance for method chaining
	 * @see #getPostprocessor()
	 */
	public CollectionProducerComputationBase setPostprocessor(BiFunction<MemoryData, Integer, PackedCollection> postprocessor) {
		this.postprocessor = postprocessor;
		return this;
	}

	/**
	 * Returns the short-circuit evaluable for this computation.
	 * The short-circuit evaluable can be used to bypass normal computation
	 * for optimization purposes, such as when the result is known in advance.
	 * 
	 * @return The short-circuit evaluable, or null if none is set
	 * @see #setShortCircuit(Evaluable)
	 */
	public Evaluable<PackedCollection> getShortCircuit() { return shortCircuit; }

	/**
	 * Sets the short-circuit evaluable for this computation.
	 * When set, this evaluable can be used to bypass normal computation
	 * for optimization purposes.
	 * 
	 * @param shortCircuit The short-circuit evaluable to set
	 * @return This computation instance for method chaining
	 * @see #getShortCircuit()
	 */
	public CollectionProducerComputationBase setShortCircuit(Evaluable<PackedCollection> shortCircuit) {
		this.shortCircuit = shortCircuit;
		return this;
	}

	/**
	 * Keeps the short-circuit for use when this computation is evaluated.
	 *
	 * @see #setShortCircuit(Evaluable)
	 */
	@Override
	public CollectionProducerComputationBase withShortCircuit(Evaluable<PackedCollection> shortCircuit) {
		return setShortCircuit(shortCircuit);
	}

	/**
	 * Returns the custom description function for this computation.
	 * This function can be used to generate human-readable descriptions
	 * of the computation for debugging and profiling purposes.
	 * 
	 * @return The description function, or null if none is set
	 * @see #setDescription(Function)
	 */
	public Function<List<String>, String> getDescription() {
		return description;
	}

	/**
	 * Sets the custom description function for this computation.
	 * This function will be used to generate human-readable descriptions
	 * based on the computation's child components.
	 * 
	 * @param description The description function to set
	 * @return This computation instance for method chaining
	 * @see #getDescription()
	 */
	public CollectionProducerComputationBase setDescription(Function<List<String>, String> description) {
		this.description = description;
		return this;
	}

	/**
	 * Creates traversable expressions for the computation arguments.
	 * This method sets up the argument access patterns for kernel execution,
	 * allowing efficient traversal of multi-dimensional input data.
	 * 
	 * @param index The index expression for the current computation position
	 * @return Array of traversable expressions for each input argument, with a {@code null}
	 *         entry for any input that has no traversable kernel argument
	 */
	protected TraversableExpression[] getTraversableArguments(Expression<?> index) {
		TraversableExpression[] vars = new TraversableExpression[getInputs().size()];
		for (int i = 0; i < vars.length; i++) {
			ArrayVariable<?> arg = getArgumentForInput(getInputs().get(i));
			vars[i] = arg == null ? null : arg.getTraversableExpression();
		}
		return vars;
	}

	/**
	 * Retrieves the collection variable for the specified argument index.
	 * This method provides access to the underlying collection structure
	 * of input arguments when they are collection-based.
	 * 
	 * @param argIndex The index of the argument to retrieve
	 * @return The collection variable, or null if the argument is not a collection variable
	 */
	public CollectionVariable<?> getCollectionArgumentVariable(int argIndex) {
		ArrayVariable<?> arg = getArgumentForInput(getInputs().get(argIndex));

		if (arg instanceof CollectionVariable) {
			return (CollectionVariable<?>) arg;
		} else {
			return null;
		}
	}

	/**
	 * Protected method to obtain the evaluable for this computation.
	 * This method handles compute requirements setup and delegates to the
	 * parent class implementation while ensuring proper resource management.
	 * 
	 * @return The evaluable for this computation
	 */
	protected Evaluable<PackedCollection> getEvaluable() {
		try {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().pushRequirements(getComputeRequirements());
			}

			return CollectionProducerComputation.super.get();
		} finally {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().popRequirements();
			}
		}
	}

	/**
	 * Returns the evaluable instance for this computation.
	 * This method implements lazy evaluation and caching of the hardware-accelerated
	 * evaluable. The evaluable is created once and reused for subsequent calls
	 * unless the computation arguments are reset.
	 * 
	 * <p>The method handles:</p>
	 * <ul>
	 *   <li>Optimization checks (delegates to optimized version if available)</li>
	 *   <li>Lazy creation of hardware evaluables with destination processing</li>
	 *   <li>Proper shape alignment and traversal axis selection</li>
	 * </ul>
	 * 
	 * @return The cached or newly created evaluable for this computation
	 * @see HardwareEvaluable
	 * @see #getEvaluable()
	 */
	@Override
	public Evaluable<PackedCollection> get() {
		if (optimized != null & optimized != this) {
			warn("This Computation should not be used, as an optimized version already exists");
			return (Evaluable<PackedCollection>) optimized.get();
		}

		if (evaluable == null) {
			this.evaluable = new HardwareEvaluable<>(
					this::getEvaluable,
					getDestination(),
					getShortCircuit(), true);
			this.evaluable.setDestinationProcessor(destination -> {
				if (destination instanceof PackedCollection) {
					PackedCollection out = (PackedCollection) destination;

					int targetSize = getMemLength();

					if (getCountLong() > 1 || isFixedCount() || (out.getShape().getCountLong() > 1 && getCountLong() == 1)) {
						for (int axis = out.getShape().getDimensions(); axis >= 0; axis--) {
							if (out.getShape().traverse(axis).getSize() == targetSize) {
								return axis == out.getShape().getTraversalAxis() ? out : out.traverse(axis);
							}
						}
					}

					if (targetSize > 1 && ((Shape) destination).getShape().getSize() != targetSize) {
						throw new IllegalArgumentException();
					}
				}

				return destination;
			});
		}

		return evaluable;
	}

	/**
	 * Post-processes the raw computation output using the configured post-processor.
	 * If no post-processor is set, delegates to the parent class implementation.
	 * 
	 * @param output The raw memory data from the computation
	 * @param offset The offset within the output data
	 * @return The post-processed output of type O
	 * @see #setPostprocessor(BiFunction)
	 */
	@Override
	public PackedCollection postProcessOutput(MemoryData output, int offset) {
		return getPostprocessor() == null ? CollectionProducerComputation.super.postProcessOutput(output, offset) : getPostprocessor().apply(output, offset);
	}

	/**
	 * Attempt to convert this computation to a {@link RepeatedProducerComputationAdapter} for
	 * sequential execution in a loop.
	 * 
	 * <p><strong>Note:</strong> Subclasses that can be meaningfully converted should
	 * override this method to provide the appropriate {@link RepeatedProducerComputationAdapter}
	 * implementation.
	 *
	 * @return Never returns normally in the base class implementation
	 * @throws UnsupportedOperationException Always thrown, as this operation is not
	 *         directly supported by the base class
	 * 
	 * @see RepeatedProducerComputationAdapter
	 * @see CollectionProducerComputationAdapter#toRepeated()
	 */
	public RepeatedProducerComputationAdapter toRepeated() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Destroys this computation and frees associated resources.
	 * This method performs cleanup of the destination producer, purges
	 * cached {@link Evaluable}s, and calls the parent class cleanup.
	 * 
	 * <p>After calling this method, the computation should not be used again.</p>
	 */
	@Override
	public void destroy() {
		super.destroy();
		getInputs().get(0).destroy();
		Destroyable.destroy(evaluable);
		this.evaluable = null;
	}

	/**
	 * Generates a signature string for this computation including shape information.
	 * The signature is used for computation caching and deduplication.
	 * It combines the base signature with the detailed shape representation.
	 *
	 * @return A signature string including shape details, or null if no base signature exists
	 * @see TraversalPolicy#toStringDetail()
	 */
	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) return null;

		return signature + getShape().toStringDetail();
	}

	/**
	 * Provides a description of this computation including its shape information.
	 * Extends the parent class description with the output shape details.
	 * 
	 * @return A string description of the computation and its shape
	 */
	@Override
	public String describe() {
		return super.describe() + " " + getShape();
	}

	/**
	 * Generates a description of this computation based on its child components.
	 * Uses the custom description function if available, otherwise delegates
	 * to the parent class implementation.
	 * 
	 * @param children List of string descriptions from child components
	 * @return A human-readable description of the computation
	 * @see #setDescription(Function)
	 */
	@Override
	public String description(List<String> children) {
		return description == null ? super.description(children) : description.apply(children);
	}

	/**
	 * Validates that all supplied argument suppliers are non-null.
	 * This utility method is used during computation construction to ensure
	 * all required arguments are properly provided.
	 * 
	 * @param args Variable argument array of suppliers to validate
	 * @return The same array of suppliers after validation
	 * @throws NullPointerException if any supplier is null
	 */
	public static Producer[] validateArgs(Producer<PackedCollection>... args) {
		Stream.of(args).forEach(Objects::requireNonNull);
		return args;
	}

	/**
	 * Executes the given {@link Runnable} with destination logging temporarily enabled.
	 * This utility method is useful for debugging memory allocation and shape
	 * adjustment operations without permanently enabling global logging.
	 * 
	 * <p>The previous logging state is restored after execution, regardless
	 * of whether the runnable completes normally or throws an exception.</p>
	 * 
	 * @param r The runnable to execute with logging enabled
	 * @see #enableDestinationLogging
	 */
	public static void destinationLog(Runnable r) {
		boolean log = enableDestinationLogging;

		try {
			enableDestinationLogging = true;
			r.run();
		} finally {
			enableDestinationLogging = log;
		}
	}
}
