/*
 * Copyright 2022 Michael Murray
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

import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedOperation;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.ctx.ContextSpecific;
import org.almostrealism.hardware.ctx.DefaultContextSpecific;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.MemoryDataAdapter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public class PackedCollection extends MemoryDataAdapter implements MemoryBank<PackedCollection> {
	private static ContextSpecific<KernelizedOperation> clear;

	static {
		clear = new DefaultContextSpecific<>(() ->
				(KernelizedOperation)
						new Assignment<>(1, new PassThroughProducer(-1, 0),
						new PassThroughProducer<>(1, 1, -1)).get());
	}

	private final TraversalPolicy shape;
	private final int traversalAxis;

	public PackedCollection(int... shape) {
		this(new TraversalPolicy(shape));
	}

	public PackedCollection(TraversalPolicy shape) {
		this(shape, 0, null, 0);
	}

	public PackedCollection(TraversalPolicy shape, int traversalAxis, MemoryData delegate, int delegateOffset) {
		this.shape = shape;
		this.traversalAxis = traversalAxis;
		setDelegate(delegate, delegateOffset);
		init();
	}

	@Override
	public PackedCollection get(int index) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void set(int index, PackedCollection value) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getCount() {
		return getMemLength() / getAtomicMemLength();
	}

	@Override
	public int getMemLength() {
		return shape.getTotalSize();
	}

	@Override
	public int getAtomicMemLength() {
		return shape.size(traversalAxis);
	}

	public TraversalPolicy getShape() { return shape; }

	public void clear() {
		clear.getValue().kernelOperate(this.traverseEach(), new PackedCollection(1));
	}

	public PackedCollection range(TraversalPolicy shape) {
		return range(shape, 0);
	}

	public PackedCollection range(TraversalPolicy shape, int start) {
		return new PackedCollection(shape, 0, this, start);
	}

	public PackedCollection traverse(int axis) {
		return new PackedCollection(shape, axis, this, 0);
	}

	public PackedCollection traverseEach() {
		return traverse(getShape().getDimensions());
	}

	public <T extends MemoryData> Stream<T> extract(IntFunction<T> factory) {
		AtomicInteger idx = new AtomicInteger();

		return Stream.generate(() -> {
			T v = factory.apply(getAtomicMemLength() / getShape().size(traversalAxis + 1));
			if (v.getMemLength() != getAtomicMemLength()) {
				throw new UnsupportedOperationException("Cannot extract entries of length " + getAtomicMemLength() +
														" into memory of length " + v.getMemLength());
			}
			// TODO  Reassigning the delegate after the MemoryData is constructed
			// TODO  results in memory being unnecessarily allocated and then left
			// TODO  unused. MemoryDataAdapter::init should be lazily invoked so
			// TODO  memory is not allocated until it is known to be needed
			v.setDelegate(this, idx.getAndIncrement() * getAtomicMemLength());
			return v;
		}).limit(getMemLength() / getAtomicMemLength());
	}

	public PackedCollection delegate(int offset, int length) {
		return new PackedCollection(new TraversalPolicy(length), 0, this, offset);
	}
}
