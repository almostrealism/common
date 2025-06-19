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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.Algebraic;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.uml.Signature;
import io.almostrealism.util.DescribableParent;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.io.Describable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A producer that reshapes collections by modifying their dimensional structure or traversal patterns.
 * This class provides two primary modes of operation: traversal axis modification and explicit shape transformation.
 * 
 * <h3>Purpose</h3>
 * The {@code ReshapeProducer} enables changing how collections are structured and accessed without copying
 * the underlying data. It supports both logical reshaping (changing dimensions while preserving total size)
 * and traversal modifications (changing iteration patterns over the same data).
 * 
 * <h3>Operation Modes</h3>
 * <ul>
 *   <li><strong>Traversal Axis Mode:</strong> Changes which dimension is used as the primary traversal axis</li>
 *   <li><strong>Shape Transformation Mode:</strong> Explicitly changes the dimensional structure of the collection</li>
 * </ul>
 * 
 * <h3>Usage Examples</h3>
 * 
 * <h4>Basic Shape Transformation</h4>
 * <pre>{@code
 * // Reshape a 1D vector into a 2D matrix
 * CollectionProducer<PackedCollection<?>> vector = c(1, 2, 3, 4, 5, 6);
 * TraversalPolicy matrixShape = shape(2, 3);
 * ReshapeProducer<PackedCollection<?>> matrix = new ReshapeProducer<>(matrixShape, vector);
 * // Result: 2x3 matrix with same data arranged as [[1,2,3], [4,5,6]]
 * }</pre>
 * 
 * <h4>Traversal Axis Modification</h4>
 * <pre>{@code
 * // Change traversal axis for different iteration patterns
 * CollectionProducer<PackedCollection<?>> matrix = c(shape(3, 4)); // 12 elements
 * ReshapeProducer<PackedCollection<?>> rowTraversal = new ReshapeProducer<>(0, matrix);
 * ReshapeProducer<PackedCollection<?>> colTraversal = new ReshapeProducer<>(1, matrix);
 * // Same data, different traversal patterns
 * }</pre>
 * 
 * <h4>Integration with Collection Operations</h4>
 * <pre>{@code
 * // Using via CollectionFeatures helper methods
 * CollectionProducer<PackedCollection<?>> data = c(shape(2, 2, 3)); // 12 elements
 * 
 * // Reshape to flatten the data
 * Producer<?> flattened = reshape(shape(12), data);
 * 
 * // Change traversal axis
 * Producer<?> reordered = traverse(1, data);
 * 
 * // Element-wise traversal
 * Producer<?> elements = traverseEach(data);
 * }</pre>
 * 
 * <h3>Important Considerations</h3>
 * <ul>
 *   <li><strong>Size Preservation:</strong> Shape transformations must preserve total element count</li>
 *   <li><strong>Performance:</strong> Operations are typically zero-copy, changing only metadata</li>
 *   <li><strong>Composability:</strong> Can be chained with other collection operations</li>
 *   <li><strong>Type Safety:</strong> Maintains type information through generic parameters</li>
 * </ul>
 * 
 * @param <T> the type of Shape being reshaped, must extend Shape
 * 
 * @see org.almostrealism.collect.CollectionFeatures#reshape(io.almostrealism.collect.TraversalPolicy, io.almostrealism.relation.Producer)
 * @see org.almostrealism.collect.CollectionFeatures#traverse(int, io.almostrealism.relation.Producer)
 * @see org.almostrealism.collect.CollectionFeatures#traverseEach(io.almostrealism.relation.Producer)
 * @see io.almostrealism.collect.TraversalPolicy
 * @see io.almostrealism.collect.Shape
 * 
 * @author Michael Murray
 */
public class ReshapeProducer<T extends Shape<T>>
		implements CollectionProducerParallelProcess<T>,
					TraversableExpression<Double>, ScopeLifecycle,
					Signature, DescribableParent<Process<?, ?>>,
					CollectionFeatures {
	/** 
	 * Controls whether traversal-based reshape operations should isolate their delegate producers.
	 * When enabled, allows for better optimization and isolation of computational processes.
	 * Default is {@code true}.
	 */
	public static boolean enableTraversalDelegateIsolation = true;
	
	/** 
	 * Controls whether shape-based reshape operations should isolate their delegate producers.
	 * When enabled, allows for better optimization and isolation of computational processes.
	 * Default is {@code true}.
	 */
	public static boolean enableShapeDelegateIsolation = true;

	/** Metadata describing this reshape operation for debugging and introspection. */
	private OperationMetadata metadata;
	
	/** The target shape for explicit shape transformations, null for traversal axis operations. */
	private TraversalPolicy shape;
	
	/** The traversal axis for axis-based reshaping, used when shape is null. */
	private int traversalAxis;
	
	/** The underlying producer whose output will be reshaped. */
	private Producer<T> producer;

	/**
	 * Creates a ReshapeProducer that modifies the traversal axis of the input producer.
	 * This constructor is used when you want to change how the collection is traversed
	 * without changing its overall dimensional structure.
	 * 
	 * <p>The traversal axis determines which dimension is used as the primary iteration
	 * axis during collection operations. Changing this can affect performance and
	 * the order in which elements are processed.</p>
	 * 
	 * @param traversalAxis the new traversal axis (0-based index into the dimensions)
	 * @param producer the source producer to reshape
	 * 
	 * @throws UnsupportedOperationException if the producer doesn't implement Shape
	 * @throws IndexOutOfBoundsException if traversalAxis is invalid for the producer's shape
	 * 
	 * <h4>Example Usage:</h4>
	 * <pre>{@code
	 * // Create a 3x4 matrix with default traversal axis 0
	 * CollectionProducer<PackedCollection<?>> matrix = c(shape(3, 4)); // 12 elements
	 * 
	 * // Change to traverse along columns (axis 1) instead of rows
	 * ReshapeProducer<PackedCollection<?>> columnTraversal = 
	 *     new ReshapeProducer<>(1, matrix);
	 * 
	 * // This affects how operations like enumeration work on the data
	 * }</pre>
	 */
	public ReshapeProducer(int traversalAxis, Producer<T> producer) {
		this.traversalAxis = traversalAxis;
		this.producer = producer;
		init();
	}

	/**
	 * Creates a ReshapeProducer that transforms the input to have an explicit new shape.
	 * This constructor is used for explicit dimensional restructuring while preserving
	 * the total number of elements.
	 * 
	 * <p>The total size of the new shape must exactly match the total size of the
	 * input producer. This ensures no data is lost or duplicated during the reshape operation.</p>
	 * 
	 * @param shape the new traversal policy defining the target dimensions
	 * @param producer the source producer to reshape
	 * 
	 * @throws IllegalArgumentException if the total sizes don't match
	 * 
	 * <h4>Example Usage:</h4>
	 * <pre>{@code
	 * // Reshape a 1D vector into a 2D matrix
	 * CollectionProducer<PackedCollection<?>> vector = c(1, 2, 3, 4, 5, 6); // size: 6
	 * TraversalPolicy matrixShape = shape(2, 3); // 2x3 = 6 elements
	 * ReshapeProducer<PackedCollection<?>> matrix = 
	 *     new ReshapeProducer<>(matrixShape, vector);
	 * 
	 * // Flatten a multi-dimensional array
	 * CollectionProducer<PackedCollection<?>> tensor = c(shape(2, 2, 2)); // 8 elements
	 * TraversalPolicy flatShape = shape(8);
	 * ReshapeProducer<PackedCollection<?>> flattened = 
	 *     new ReshapeProducer<>(flatShape, tensor);
	 * }</pre>
	 */
	public ReshapeProducer(TraversalPolicy shape, Producer<T> producer) {
		this.shape = shape;
		this.producer = producer;

		if (shape(producer).getTotalSizeLong() != shape.getTotalSizeLong()) {
			throw new IllegalArgumentException();
		}

		init();
	}

	/**
	 * Initializes the reshape operation metadata and performs validation.
	 * This method sets up operation metadata for debugging and optimization purposes,
	 * and warns about potentially inefficient operations like reshaping constants.
	 * 
	 * <p>The metadata helps with operation tracking, debugging, and optimization
	 * in complex computational graphs.</p>
	 */
	protected void init() {
		if (producer instanceof CollectionConstantComputation) {
			warn("Reshaping of constant");
		}

		if (producer instanceof OperationInfo) {
			OperationMetadata child = ((OperationInfo) producer).getMetadata();

			if (child == null) {
				return;
			} else {
				metadata = new OperationMetadata("reshape(" + child.getDisplayName() + ")",
						extendDescription(child.getShortDescription(), false));
			}

			metadata = new OperationMetadata(metadata, List.of(child));
		} else {
			metadata = new OperationMetadata("reshape", "reshape");
		}
	}

	/**
	 * Extends an operation description with reshape-specific information.
	 * This method adds information about the target shape or traversal axis
	 * to help identify and debug reshape operations.
	 * 
	 * @param description the base description to extend
	 * @param brief whether to use a brief format (excludes traversal axis details)
	 * @return the extended description with reshape information
	 */
	protected String extendDescription(String description, boolean brief) {
		if (shape != null) {
			return description + "{->" + getShape() + "}";
		} else if (!brief) {
			return description + "{->" + traversalAxis + "}";
		} else {
			return description;
		}
	}

	/**
	 * Returns the operation metadata for this reshape producer.
	 * This metadata contains information about the operation for debugging,
	 * optimization, and introspection purposes.
	 * 
	 * @return the operation metadata, or null if not initialized
	 */
	@Override
	public OperationMetadata getMetadata() { return metadata; }

	/**
	 * Returns the traversal policy representing the shape of this reshaped collection.
	 * The returned shape depends on the mode of operation:
	 * <ul>
	 *   <li>For explicit shape mode: returns the explicitly set shape</li>
	 *   <li>For traversal axis mode: returns the input shape with modified traversal axis</li>
	 * </ul>
	 * 
	 * @return the traversal policy defining the dimensions and traversal pattern
	 * @throws UnsupportedOperationException if using traversal axis mode but producer doesn't implement Shape
	 * 
	 * <h4>Example:</h4>
	 * <pre>{@code
	 * // Explicit shape mode
	 * ReshapeProducer<PackedCollection<?>> matrix = 
	 *     new ReshapeProducer<>(shape(2, 3), vectorProducer);
	 * TraversalPolicy matrixShape = matrix.getShape(); // Returns shape(2, 3)
	 * 
	 * // Traversal axis mode
	 * ReshapeProducer<PackedCollection<?>> reordered = 
	 *     new ReshapeProducer<>(1, matrixProducer);
	 * TraversalPolicy reorderedShape = reordered.getShape(); // Input shape with axis 1 traversal
	 * }</pre>
	 */
	@Override
	public TraversalPolicy getShape() {
		if (shape == null) {
			if (!(producer instanceof Shape)) {
				throw new UnsupportedOperationException();
			}

			TraversalPolicy inputShape = ((Shape) producer).getShape();
			return inputShape.traverse(traversalAxis);
		} else {
			return shape;
		}
	}

	/**
	 * Returns whether this reshape operation represents a constant value.
	 * The result depends on whether the underlying producer is constant.
	 * 
	 * @return true if the underlying producer is constant, false otherwise
	 */
	@Override
	public boolean isConstant() {
		return producer.isConstant();
	}

	/**
	 * Returns whether this reshape operation represents a zero value.
	 * For algebraic producers, delegates to the underlying producer's zero check.
	 * 
	 * @return true if the reshaped collection represents zero values
	 */
	@Override
	public boolean isZero() {
		if (producer instanceof Algebraic) {
			return ((Algebraic) producer).isZero();
		}

		return TraversableExpression.super.isZero();
	}

	@Override
	public boolean isIdentity(int width) {
		if (producer instanceof Algebraic) {
			return ((Algebraic) producer).isIdentity(width);
		}

		return TraversableExpression.super.isIdentity(width);
	}

	@Override
	public boolean isDiagonal(int width) {
		if (producer instanceof Algebraic) {
			return ((Algebraic) producer).isDiagonal(width);
		}

		return TraversableExpression.super.isDiagonal(width);
	}

	@Override
	public Optional<Computable> getDiagonalScalar(int width) {
		if (producer instanceof Algebraic) {
			return ((Algebraic) producer).getDiagonalScalar(width);
		}

		return TraversableExpression.super.getDiagonalScalar(width);
	}

	@Override
	public long getParallelism() {
		if (producer instanceof ParallelProcess) {
			return ((ParallelProcess) producer).getParallelism();
		}

		return 1;
	}

	@Override
	public long getOutputSize() {
		if (producer instanceof Process) {
			return ((Process) producer).getOutputSize();
		}

		return 0;
	}

	@Override
	public long getCountLong() { return getShape().getCountLong(); }

	@Override
	public boolean isFixedCount() {
		return Countable.isFixedCount(producer);
	}

	/**
	 * Returns the underlying computation producer, unwrapping nested ReshapeProducers.
	 * This method helps optimize chains of reshape operations by accessing the
	 * root computation directly.
	 * 
	 * @return the underlying producer, or the nested computation if this wraps another ReshapeProducer
	 * 
	 * <h4>Example:</h4>
	 * <pre>{@code
	 * // Chain of reshape operations
	 * ReshapeProducer<PackedCollection<?>> first = new ReshapeProducer<>(shape(2, 3), baseProducer);
	 * ReshapeProducer<PackedCollection<?>> second = new ReshapeProducer<>(1, first);
	 * 
	 * Producer<PackedCollection<?>> root = second.getComputation();
	 * // Returns baseProducer, skipping the intermediate reshape
	 * }</pre>
	 */
	public Producer<T> getComputation() {
		if (producer instanceof ReshapeProducer) {
			return ((ReshapeProducer) producer).getComputation();
		} else {
			return producer;
		}
	}

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return producer instanceof Process ? List.of((Process) producer) : Collections.emptyList();
	}

	@Override
	public ReshapeProducer<T> generate(List<Process<?, ?>> children) {
		if (children.size() != 1) return this;

		return shape == null ?
				new ReshapeProducer<>(traversalAxis, (Producer<T>) children.get(0)) :
				new ReshapeProducer<>(shape, (Producer<T>) children.get(0));
	}

	@Override
	public ParallelProcess<Process<?, ?>, Evaluable<? extends T>> optimize(ProcessContext ctx) {
		if (producer instanceof Process) {
			return generateReplacement(List.of(optimize(ctx, ((Process) producer))));
		}

		return this;
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		if (shape == null) {
			if (enableTraversalDelegateIsolation && producer instanceof Process) {
				Process<?, ?> isolated = ((Process<?, ?>) this.producer).isolate();

				if (isolated != producer) {
					return generateReplacement(List.of(isolated));
				}
			}

			return CollectionProducerComputation.isIsolationPermitted(this) ?
					new CollectionProducerComputation.IsolatedProcess(this) :
					this;
		} else {
			if (enableShapeDelegateIsolation && producer instanceof Process) {
				Process<?, ?> isolated = ((Process<?, ?>) this.producer).isolate();

				if (isolated != producer) {
					return generateReplacement(List.of(isolated));
				}
			}

			return this;
		}
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		if (producer instanceof ScopeLifecycle) {
			((ScopeLifecycle) producer).prepareArguments(map);
		}
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		if (producer instanceof ScopeLifecycle) {
			((ScopeLifecycle) producer).prepareScope(manager, context);
		}
	}

	@Override
	public void resetArguments() {
		if (producer instanceof ScopeLifecycle) {
			((ScopeLifecycle) producer).resetArguments();
		}
	}

	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		if (shape != null && shape.getOrder() != null) {
			// TODO
			throw new UnsupportedOperationException();
		}

		return producer instanceof TraversableExpression ?
				((TraversableExpression) producer).getValueAt(index) :
				TraversableExpression.super.containsIndex(index);
	}

	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return producer instanceof TraversableExpression ? ((TraversableExpression) producer).getValueAt(index) : null;
	}

	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return producer instanceof TraversableExpression ? ((TraversableExpression) producer).getValueRelative(index) : null;
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return producer instanceof TraversableExpression ?
				((TraversableExpression) producer).uniqueNonZeroOffset(globalIndex, localIndex, targetIndex) :
				null;
	}

	@Override
	public Expression uniqueNonZeroIndexRelative(Index localIndex, Expression<?> targetIndex) {
		return producer instanceof TraversableExpression ?
				((TraversableExpression) producer).uniqueNonZeroIndexRelative(localIndex, targetIndex) :
				TraversableExpression.super.uniqueNonZeroIndexRelative(localIndex, targetIndex);
	}

	@Override
	public boolean isTraversable() {
		if (producer instanceof TraversableExpression) return ((TraversableExpression) producer).isTraversable();
		return false;
	}

	@Override
	public boolean isRelative() {
		if (producer instanceof TraversableExpression) return ((TraversableExpression) producer).isRelative();
		return true;
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (producer instanceof CollectionProducer) {
			if (shape == null) {
				return traverse(traversalAxis, ((CollectionProducer) producer).delta(target));
			} else {
				TraversalPolicy newShape = shape.append(shape(target));
				return (CollectionProducer) reshape(newShape, ((CollectionProducer) producer).delta(target));
			}
		}

		return CollectionProducerParallelProcess.super.delta(target);
	}

	/**
	 * Creates a new ReshapeProducer with a different traversal axis.
	 * This method provides a fluent interface for changing traversal patterns
	 * while optimizing for cases where the change can be applied directly to
	 * the underlying producer.
	 * 
	 * @param axis the new traversal axis to use
	 * @return a new ReshapeProducer with the specified traversal axis
	 * 
	 * <h4>Usage:</h4>
	 * <pre>{@code
	 * ReshapeProducer<PackedCollection<?>> matrix = 
	 *     new ReshapeProducer<>(shape(3, 4), baseProducer);
	 * 
	 * // Change to traverse along axis 1 (columns)
	 * CollectionProducer<PackedCollection<?>> columnTraversal = matrix.traverse(1);
	 * }</pre>
	 */
	public CollectionProducer<T> traverse(int axis) {
		if (shape == null || shape(producer).traverse(0).equals(getShape().traverse(0))) {
			return new ReshapeProducer<>(axis, producer);
		} else {
			return new ReshapeProducer<>(axis, this);
		}
	}

	/**
	 * Creates a new ReshapeProducer with an explicit target shape.
	 * This method provides a fluent interface for chaining reshape operations
	 * while optimizing by applying the reshape directly to the underlying producer
	 * when possible.
	 * 
	 * @param shape the new traversal policy defining the target dimensions
	 * @return a new ReshapeProducer with the specified shape
	 * @throws IllegalArgumentException if the new shape has incompatible total size
	 * 
	 * <h4>Usage:</h4>
	 * <pre>{@code
	 * ReshapeProducer<PackedCollection<?>> intermediate = 
	 *     new ReshapeProducer<>(1, baseProducer);
	 * 
	 * // Further reshape to a specific 2D layout
	 * CollectionProducer<PackedCollection<?>> finalShape = 
	 *     intermediate.reshape(shape(2, 6));
	 * }</pre>
	 */
	@Override
	public CollectionProducer<T> reshape(TraversalPolicy shape) {
		return new ReshapeProducer<>(shape, producer);
	}

	@Override
	public Evaluable<T> get() {
		Evaluable<T> ev = producer.get();

		if (ev instanceof Provider) {
			return p((Provider) ev, v -> (Shape<T>) apply((T) v));
		}

		HardwareEvaluable<T> hev = new HardwareEvaluable<>(producer::get, null, null, false);
		hev.setShortCircuit(args -> {
			long start = System.nanoTime();
			Shape<T> out = hev.getKernel().getValue().evaluate(args);
			AcceleratedOperation.wrappedEvalMetric.addEntry(producer, System.nanoTime() - start);
			return apply(out);
		});
		return hev;
	}

	@Override
	public String signature() {
		String signature = Signature.of(getComputation());
		if (signature == null) return null;

		return signature + "|" + getShape().toStringDetail();
	}

	/**
	 * Returns a detailed description of this reshape operation including shape information.
	 * This method provides comprehensive debugging information about the reshape operation,
	 * including the target shape and its detailed traversal policy.
	 * 
	 * @return a detailed description string combining operation description and shape details
	 */
	@Override
	public String describe() {
		return description() + " | " + getShape().toStringDetail();
	}

	/**
	 * Returns a concise description of this reshape operation.
	 * The description format varies based on the type of underlying producer
	 * and includes information about the target shape or traversal axis.
	 * 
	 * @return a concise description of the reshape operation
	 */
	@Override
	public String description() {
		if (producer instanceof CollectionProviderProducer) {
			return "p" + getShape().toString();
		} else if (producer instanceof DescribableParent) {
			return extendDescription(((DescribableParent) producer).description(), true);
		} else {
			return extendDescription(Describable.describe(producer), true);
		}
	}

	/**
	 * Applies the reshape transformation to a concrete Shape instance.
	 * This method performs the actual reshaping logic, choosing between
	 * traversal axis modification and explicit shape transformation based
	 * on the operation mode.
	 * 
	 * @param in the input shape to transform
	 * @return the reshaped result
	 */
	private T apply(Shape<T> in) {
		if (shape == null) {
			return in.reshape(in.getShape().traverse(traversalAxis));
		} else {
			return in.reshape(shape);
		}
	}
}
