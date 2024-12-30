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

package org.almostrealism.collect.computations;

import io.almostrealism.code.Computation;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.computations.HardwareEvaluable;

import java.util.List;
import java.util.OptionalDouble;

public class PackedCollectionRepeat<T extends PackedCollection<?>>
		extends IndexProjectionProducerComputation<T> {
	public static boolean enableUniqueIndexOptimization = true;
	public static boolean enableInputIsolation = true;
	public static boolean enableIsolation = true;
	public static boolean enableLargeSlice = true;
	public static boolean enableShortCircuit = false;

	private TraversalPolicy subsetShape;
	private TraversalPolicy sliceShape;

	public PackedCollectionRepeat(int repeat, Producer<?> collection) {
		this(shape(collection).item(), repeat, collection);
	}

	public PackedCollectionRepeat(TraversalPolicy shape, int repeat, Producer<?> collection) {
		super("repeat" + repeat, shape(collection).replace(shape.prependDimension(repeat)).traverse(),
				null, collection);
		this.subsetShape = shape.getDimensions() == 0 ? shape(1) : shape;
		this.sliceShape = subsetShape.prependDimension(repeat);

		if (!enableLargeSlice &&
				(!isFixedCount() || sliceShape.getTotalSizeLong() < getShape().getTotalSizeLong()) &&
				sliceShape.getTotalSizeLong() > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException();
		}
	}

	private PackedCollectionRepeat(TraversalPolicy shape, TraversalPolicy subsetShape,
								   TraversalPolicy sliceShape, Producer<?> collection) {
		super("repeat", shape, null, collection);
		this.subsetShape = subsetShape;
		this.sliceShape = sliceShape;
	}

	@Override
	protected Expression projectIndex(Expression index) {
		Expression slice;
		Expression offset;

		if (!isFixedCount() || sliceShape.getTotalSizeLong() < getShape().getTotalSizeLong()) {
			// Identify the output slice
			if (sliceShape.getTotalSizeLong() == 1) {
				slice = index;
			} else if (!index.isFP()) {
				slice = index.divide(e(sliceShape.getTotalSizeLong()));
			} else {
				slice = index.divide(e((double) sliceShape.getTotalSizeLong())).floor();
			}

			// Find the index in the output slice
			offset = index.toInt().imod(sliceShape.getTotalSizeLong());
		} else {
			// There is only one slice
			slice = e(0);
			offset = index;
		}

		// Find the index in the input slice
		offset = offset.imod(subsetShape.getTotalSizeLong());

		// Position the offset relative to the slice
		offset = slice.multiply(e(subsetShape.getTotalSizeLong())).add(offset);

		return offset;
	}

	// TODO  Remove
	@Override
	public Expression<Double> getValueRelative(Expression index) {
		Expression offset = projectIndex(index);
		OptionalDouble offsetValue = offset.getSimplified().doubleValue();
		if (offsetValue.isEmpty()) throw new UnsupportedOperationException();

		return getArgument(1).getValueRelative((int) offsetValue.getAsDouble());
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (!enableUniqueIndexOptimization || sliceShape.getTotalSizeLong() < getShape().getTotalSizeLong())
			return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);

		if (localIndex.getLimit().isEmpty() || globalIndex.getLimit().isEmpty()) return null;
		if (subsetShape.getTotalSizeLong() % localIndex.getLimit().getAsLong() != 0) return null;

		long limit = getShape().getTotalSizeLong() / globalIndex.getLimit().getAsLong();
		DefaultIndex g = new DefaultIndex(getVariablePrefix() + "_g", limit);
		DefaultIndex l = new DefaultIndex(getVariablePrefix() + "_l", localIndex.getLimit().getAsLong());

		Expression idx = getCollectionArgumentVariable(1).uniqueNonZeroOffset(g, l, Index.child(g, l));
		if (idx == null) return idx;
		if (!idx.isValue(IndexValues.of(g))) return null;

		// return idx.withIndex(g, ((Expression<?>) globalIndex).divide(sliceShape.getCount()));
		return idx.withIndex(g, ((Expression<?>) globalIndex).imod(limit));
	}

	@Override
	public Evaluable<T> get() {
		if (!enableShortCircuit || sliceShape.getTotalSizeLong() != getShape().getTotalSizeLong()) {
			return super.get();
		}

		Evaluable<T> ev = (Evaluable) getInputs().get(1).get();

		int r = Math.toIntExact(getShape().getTotalSizeLong() / subsetShape.getTotalSizeLong());

		if (ev instanceof Provider) {
			return p((Provider) ev, v ->
					(T) ((PackedCollection) v).repeat(r));
		}

		HardwareEvaluable<T> hev = new HardwareEvaluable(getInputs().get(1)::get, null, null, false);
		hev.setShortCircuit(args -> {
			T out = hev.getKernel().getValue().evaluate(args);
			return (T) out.repeat(r);
		});
		return hev;
	}

	@Override
	public PackedCollectionRepeat<T> generate(List<Process<?, ?>> children) {
		return new PackedCollectionRepeat<>(getShape(), subsetShape, sliceShape, (Producer<?>) children.get(1));
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		Producer in = (Producer) getInputs().get(1);
		if (in instanceof ReshapeProducer) in = ((ReshapeProducer<?>) in).getComputation();

		boolean computable = in instanceof Computation;

		if (!enableIsolation && !computable) {
			PackedCollectionRepeat<T> isolated = (PackedCollectionRepeat<T>)
					generateReplacement(List.of((Process) getInputs().get(0), (Process) getInputs().get(1)));
			return isolated;
		}

		if (!enableInputIsolation || !computable)
			return super.isolate();

		PackedCollectionRepeat<T> isolated = (PackedCollectionRepeat<T>)
				generateReplacement(List.of((Process) getInputs().get(0), isolate((Process) getInputs().get(1))));

		return enableIsolation ? (Process) Process.isolated(isolated) : isolated;
	}

	@Override
	public String description(List<String> children) {
		return children.size() == 1 ? children.get(0) : super.description(children);
	}

	private static TraversalPolicy shape(Producer<?> collection) {
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Repeat cannot be performed without a TraversalPolicy");

		return ((Shape) collection).getShape();
	}
}
