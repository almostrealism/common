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
import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;
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
					TraversableExpression<Double>,
					ScopeLifecycle, DescribableParent<Process<?, ?>>, CollectionFeatures {
	public static boolean enableTraversalDelegateIsolation = true;
	public static boolean enableShapeDelegateIsolation = true;

	private OperationMetadata metadata;
	private TraversalPolicy shape;
	private int traversalAxis;
	private Producer<T> producer;

	public ReshapeProducer(int traversalAxis, Producer<T> producer) {
		this.traversalAxis = traversalAxis;
		this.producer = producer;
		init();
	}

	public ReshapeProducer(TraversalPolicy shape, Producer<T> producer) {
		this.shape = shape;
		this.producer = producer;

		if (shape(producer).getTotalSizeLong() != shape.getTotalSizeLong()) {
			throw new IllegalArgumentException();
		}

		init();
	}

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

	protected String extendDescription(String description, boolean brief) {
		if (shape != null) {
			return description + "{->" + getShape() + "}";
		} else if (!brief) {
			return description + "{->" + traversalAxis + "}";
		} else {
			return description;
		}
	}

	@Override
	public OperationMetadata getMetadata() { return metadata; }

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

	@Override
	public boolean isConstant() {
		return producer.isConstant();
	}

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

	public CollectionProducer<T> traverse(int axis) {
		if (shape == null || shape(producer).traverse(0).equals(getShape().traverse(0))) {
			return new ReshapeProducer<>(axis, producer);
		} else {
			return new ReshapeProducer<>(axis, this);
		}
	}

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
	public String describe() {
		return description() + " | " + getShape().toStringDetail();
	}

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

	private T apply(Shape<T> in) {
		if (shape == null) {
			return in.reshape(in.getShape().traverse(traversalAxis));
		} else {
			return in.reshape(shape);
		}
	}
}
