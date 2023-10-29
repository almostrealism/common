/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.algebra;

import io.almostrealism.relation.Producer;

import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.Heap;

import java.util.function.BiFunction;

public class Pair<T extends PackedCollection> extends PackedCollection<T> {
	public Pair() {
		super(2);
	}

	public Pair(MemoryData delegate, int delegateOffset) {
		super(new TraversalPolicy(2), 0, delegate, delegateOffset);
	}

	public Pair(double x, double y) {
		this();
		this.setMem(new double[] { x, y });
	}
	
	public Pair setX(double x) {
		setMem(0, x);
		return this;
	}

	public Pair setY(double y) {
		setMem(1, y);
		return this;
	}

	public Pair setA(double a) { this.setX(a); return this; }
	public Pair setB(double b) { this.setY(b); return this; }
	public Pair setLeft(double l) { this.setX(l); return this; }
	public Pair setRight(double r) { this.setY(r); return this; }
	public Pair setTheta(double t) { this.setX(t); return this; }
	public Pair setPhi(double p) { this.setY(p); return this; }

	public double getX() {
		return getMem().toArray(getOffset(), 2)[0];
	}

	public double getY() {
		return getMem().toArray(getOffset(), 2)[1];
	}

	public double getA() { return getX(); }
	public double getB() { return getY(); }
	public double getLeft() { return getX(); }
	public double getRight() { return getY(); }
	public double getTheta() { return getX(); }
	public double getPhi() { return getY(); }
	public double x() { return getX(); }
	public double y() { return getY(); }
	public double a() { return getX(); }
	public double b() { return getY(); }
	public double left() { return getX(); }
	public double right() { return getY(); }
	public double theta() { return getX(); }
	public double phi() { return getY(); }
	public double r() { return getX(); }
	public double i() { return getY(); }
	public double _1() { return getX(); }
	public double _2() { return getY(); }

	/**
	 * Returns an integer hash code value for this {@link Pair} obtained
	 * by adding both components and casting to an int.
	 */
	@Override
	public int hashCode() {
		return (int) (this.getX() + this.getY());
	}

	/**
	 * Returns true if and only if the object specified is a {@link Pair}
	 * with equal values as this {@link Pair}.
	 */
	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Pair == false)
			return false;

		Pair pair = (Pair) obj;
		return pair.getX() == this.getX() && pair.getY() == this.getY();
	}

	@Override
	public int getMemLength() {
		return 2;
	}

	@Override
	public Heap getDefaultDelegate() { return Heap.getDefault(); }

	/** @return A String representation of this Vector object. */
	@Override
	public String toString() {
		return "[" +
				Defaults.displayFormat.format(getX()) +
				", " +
				Defaults.displayFormat.format(getY()) +
				"]";
	}

	public static TraversalPolicy shape() {
		return new TraversalPolicy(2);
	}

	public static Producer<Pair<?>> empty() {
		return new DynamicProducerForMemoryData<>(Pair::new, Pair::bank);
	}

	public static PackedCollection<Pair<?>> bank(int count) {
		return new PackedCollection<>(new TraversalPolicy(count, 2), 1, delegateSpec ->
				new Pair<>(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	public static PackedCollection<Pair<?>> bank(int count, MemoryData delegate, int delegateOffset) {
		return new PackedCollection<>(new TraversalPolicy(count, 2), 1, delegateSpec ->
				new Pair<>(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}

	public static PackedCollection<PackedCollection<Pair<?>>> table(int width, int count) {
		return new PackedCollection<>(new TraversalPolicy(count, width, 2), 1, delegateSpec ->
				Pair.bank(width, delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	public static PackedCollection<PackedCollection<Pair<?>>> table(int width, int count, MemoryData delegate, int delegateOffset) {
		return new PackedCollection<>(new TraversalPolicy(count, width, 2), 1, delegateSpec ->
				Pair.bank(width, delegateSpec.getDelegate(), delegateSpec.getOffset()), delegate, delegateOffset);
	}

	public static BiFunction<MemoryData, Integer, Pair<?>> postprocessor() {
		return (delegate, offset) -> new Pair<>(delegate, offset);
	}

	public static BiFunction<MemoryData, Integer, PackedCollection<Pair<?>>> bankPostprocessor() {
		return (output, offset) -> {
			TraversalPolicy shape = ((PackedCollection) output).getShape();
			return Pair.bank(shape.getTotalSize() / 2, output, offset);
		};
	}

	public static ExpressionComputation<Pair<?>> postprocess(ExpressionComputation c) {
		c.setPostprocessor(postprocessor());
		return c;
	}
}
