/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.algebra.computations;

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.code.AtomicAccumulation;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.OrderingSegments;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.NotEquals;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.List;
import java.util.Set;

/**
 * Adds, into a destination vector, the product of a source-major sparse matrix with a sparse
 * activity vector, visiting only the stored entries of the sources that are active: the
 * event-driven counterpart of {@link SegmentedWeightedSumComputation}.
 *
 * <h2>Mathematical Operation</h2>
 * <p>The matrix has shape {@code (sources, targets)} and its {@link TraversalOrdering}
 * {@link TraversalOrdering#segments(int) enumerates its rows}, so that row {@code j} lists the
 * outgoing entries of source {@code j}: the position of each entry's target is its
 * {@link OrderingSegments#offset(Expression) offset}. For every source whose activity is not
 * zero, each of its entries is added into the destination at its target:</p>
 * <pre>
 * for j with activity[j] != 0:
 *     for p = start(j) .. start(j) + length(j) - 1:
 *         destination[offset(p)] += matrix[p] * activity[j]
 * </pre>
 * <p>The amount added into the destination is therefore {@code transpose(matrix) * activity},
 * the same vector the forward product {@code matmul(transpose(matrix), activity)} produces,
 * but the work is proportional to the number of entries of the active sources rather than to
 * the number of stored entries. For the connectome {@code W[post, pre]}, the matrix is
 * {@code transpose(W)}, whose rows are the presynaptic neurons, and the activity is the vector
 * of spikes; when only a small fraction of the neurons fire, almost every source does no work.</p>
 *
 * <h2>Generated Code Structure</h2>
 * <p>One kernel thread handles one source: the kernel range is the number of sources, not the
 * size of the destination. Two active sources may have an entry for the same target, and their
 * threads then add into the same element concurrently, so every addition is an
 * {@link AtomicAccumulation}:</p>
 * <pre>
 * if (activity[j] != 0) {
 *     for (int k = 0; k &lt; length(j);) {
 *         atomic(destination[offset(start(j) + k)] += matrix[start(j) + k] * activity[j]);
 *         k = k + 1;
 *     }
 * }
 * </pre>
 * <p>The ordering of the matrix is {@link TraversalOrdering#bind(ArgumentProvider) bound} to
 * the arguments of the kernel, as it is for the forward product, so the row offsets and target
 * positions of a compressed-sparse-row matrix are delivered to the kernel in the integer-exact
 * layout the ordering stores them in.</p>
 *
 * <h2>Accumulation, not assignment</h2>
 * <p>This operation adds into whatever its destination already holds, which is what makes it a
 * scatter-add rather than a product: a destination reused across steps still holds the previous
 * step's values. The destination must be reset, in a separate operation that completes before
 * this one runs, whenever the product alone is wanted. The reset cannot be part of this kernel,
 * because the threads of a kernel are not ordered against one another: one thread could clear
 * an element after another had already added into it. A computation that writes the destination
 * must also never be fused into one kernel with this operation, for the same reason.
 * {@link org.almostrealism.algebra.MatrixFeatures#scatterProduct(Producer, Producer, Producer)}
 * composes the reset and the scatter-add so that they run as two operations, in order.</p>
 *
 * <h2>Exactness</h2>
 * <p>The order in which concurrent additions land is not defined. The result is exact whenever
 * every partial sum is exactly representable, as it is for integer-valued entries and a binary
 * activity whose partial sums stay below {@code 2^24} in magnitude at single precision; for
 * general real values the result agrees with the forward product to within rounding.</p>
 *
 * @see SegmentedWeightedSumComputation
 * @see AtomicAccumulation
 * @see org.almostrealism.algebra.MatrixFeatures#scatterProduct(Producer, Producer, Producer)
 *
 * @author Michael Murray
 */
public class SegmentedScatterAddComputation extends OperationComputationAdapter<PackedCollection> {
	/** Position of the destination among the inputs. */
	private static final int DESTINATION = 0;

	/** Position of the source-major matrix among the inputs. */
	private static final int MATRIX = 1;

	/** Position of the activity of the sources among the inputs. */
	private static final int ACTIVITY = 2;

	/** The number of sources, which is the number of kernel threads. */
	private final int sources;

	/** The number of targets, which is the length of each row of the matrix. */
	private final int targets;

	/** The ordering of the matrix, which enumerates its rows. */
	private final TraversalOrdering order;

	/** The enumeration of the rows, bound to the arguments of the kernel being compiled. */
	private OrderingSegments segments;

	/**
	 * Creates the scatter-add of the product of the given matrix and activity into the given
	 * destination.
	 *
	 * @param destination the vector of {@code targets} elements accumulated into
	 * @param matrix      the matrix, of shape {@code (sources, targets)}, whose ordering
	 *                    enumerates its rows, so that row {@code j} lists the entries of source
	 *                    {@code j}
	 * @param activity    the activity of each of the {@code sources} sources
	 * @throws IllegalArgumentException if the matrix is not two-dimensional, its ordering does not
	 *         enumerate its rows, or the destination or activity does not match its shape
	 */
	public SegmentedScatterAddComputation(Producer<PackedCollection> destination,
										  Producer<PackedCollection> matrix,
										  Producer<PackedCollection> activity) {
		this(validatedMatrixShape(destination, matrix, activity), destination, matrix, activity);
	}

	/**
	 * Creates the scatter-add for a matrix of the given, already validated, shape.
	 *
	 * @param matrixShape the shape of the matrix, carrying its ordering
	 * @param destination the destination
	 * @param matrix      the matrix
	 * @param activity    the activity
	 */
	private SegmentedScatterAddComputation(TraversalPolicy matrixShape,
										   Producer<PackedCollection> destination,
										   Producer<PackedCollection> matrix,
										   Producer<PackedCollection> activity) {
		super(destination, matrix, activity);
		this.sources = matrixShape.length(0);
		this.targets = matrixShape.length(1);
		this.order = matrixShape.getOrder();
	}

	/**
	 * Returns the shape of the matrix, rejecting a matrix whose rows cannot be enumerated and a
	 * destination or activity whose size does not match it before either reaches the index
	 * arithmetic of the kernel.
	 *
	 * @param destination the destination
	 * @param matrix      the matrix
	 * @param activity    the activity
	 * @return the shape of the matrix
	 * @throws IllegalArgumentException if the operands do not describe a scatter-add
	 */
	private static TraversalPolicy validatedMatrixShape(Producer<PackedCollection> destination,
														Producer<PackedCollection> matrix,
														Producer<PackedCollection> activity) {
		CollectionFeatures features = CollectionFeatures.getInstance();
		TraversalPolicy matrixShape = features.shape(matrix);

		if (matrixShape.getDimensions() != 2) {
			throw new IllegalArgumentException("Cannot scatter the entries of " + matrixShape);
		}

		TraversalOrdering order = matrixShape.getOrder();
		if (order == null || order.segments(matrixShape.length(1)).isEmpty()) {
			throw new IllegalArgumentException("The ordering of " + matrixShape + " does not enumerate its rows");
		}

		long destinationSize = features.shape(destination).getTotalSizeLong();
		long activitySize = features.shape(activity).getTotalSizeLong();
		if (destinationSize != matrixShape.length(1) || activitySize != matrixShape.length(0)) {
			throw new IllegalArgumentException("Cannot scatter " + matrixShape + " with an activity of " +
					activitySize + " into a destination of " + destinationSize);
		}

		return matrixShape;
	}

	/**
	 * Returns the number of sources, one kernel thread for each.
	 *
	 * @return the number of rows of the matrix
	 */
	public int getSourceCount() { return sources; }

	/**
	 * Returns the number of targets, the size of the destination.
	 *
	 * @return the number of columns of the matrix
	 */
	public int getTargetCount() { return targets; }

	/**
	 * Returns the number of sources: the kernel launches one thread per source, whatever the size
	 * of its destination.
	 *
	 * @return the number of sources
	 */
	@Override
	public long getCountLong() { return sources; }

	/**
	 * Always returns true: the kernel range is the number of sources, fixed by the matrix.
	 *
	 * @return true
	 */
	@Override
	public boolean isFixedCount() { return true; }

	/**
	 * Always returns true: the scatter owns its kernel. Its loop has a data-dependent bound and
	 * its atomic writes race with any other write of the destination in the same kernel, so when
	 * a list containing it is optimized the scatter is isolated, and that list is then
	 * dispatched as separate operations, in order, rather than fused into one kernel.
	 *
	 * @param context the process context
	 * @return true
	 */
	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		return true;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Also binds the ordering of the matrix to the arguments of the kernel, and obtains the
	 * enumeration of its rows from the bound ordering.</p>
	 */
	@Override
	public void prepareScope(ArgumentProvider manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		segments = order.bind(manager).segments(targets).orElseThrow();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Also counts the {@link TraversalOrdering#getArgumentCount() arguments} binding the
	 * ordering of the matrix obtains.</p>
	 */
	@Override
	public int getScopeArgumentCount(int cap) {
		return (int) Math.min(Integer.MAX_VALUE, (long) super.getScopeArgumentCount(cap) + order.getArgumentCount());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Also discards the enumeration bound by
	 * {@link #prepareScope(ArgumentProvider, KernelStructureContext)}.</p>
	 */
	@Override
	public void resetArguments(Set<ScopeLifecycle> visited) {
		super.resetArguments(visited);
		segments = null;
	}

	/**
	 * Builds the kernel in which each thread scatters the entries of its source, if the source is
	 * active, into the destination with atomic additions.
	 *
	 * @param context the kernel structure context
	 * @return the scatter-add scope
	 */
	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		Scope<Void> scope = super.getScope(context);

		Expression<?> source = new KernelIndex(context);
		TraversableExpression<?> activities = getArgument(ACTIVITY).getTraversableExpression();
		Expression<?> activity = activities == null ?
				getArgument(ACTIVITY).reference(source) : activities.getValueAt(source);

		String k = getVariablePrefix() + "_k";
		DefaultIndex entry = new DefaultIndex(k);
		Expression<?> position = segments.start(source).add(entry);

		ArrayVariable<?> destination = getArgument(DESTINATION);
		Expression contribution = getArgument(MATRIX).reference(position).multiply(activity);
		Expression target = destination.reference(segments.offset(position));

		Scope<Void> body = new Scope<>(getFunctionName() + "_body");
		body.getStatements().add(new AtomicAccumulation<>(target, contribution));

		Repeated<Void> loop = new Repeated<>(new Variable<>(k), entry.lessThan(segments.length(source)));
		loop.add(body);

		Scope<Void> active = new Scope<>();
		active.add(loop);
		scope.addCase(NotEquals.of(activity, new DoubleConstant(0.0)), active);
		return scope;
	}

	/**
	 * Creates the scatter-add of new operands, in the order destination, matrix, activity.
	 *
	 * @param children the three operands
	 * @return the new operation
	 */
	@Override
	public ParallelProcess<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		return new SegmentedScatterAddComputation(
				(Producer<PackedCollection>) children.get(DESTINATION),
				(Producer<PackedCollection>) children.get(MATRIX),
				(Producer<PackedCollection>) children.get(ACTIVITY));
	}

	/**
	 * Extends the signature with the class, so that a cached kernel is never shared with another
	 * operation of the same inputs.
	 *
	 * @return the signature including the class, or null when the parent signature is unavailable
	 */
	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) return null;
		return signature + "{" + getClass().getName() + "}";
	}
}
