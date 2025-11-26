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

package org.almostrealism.algebra;

import io.almostrealism.util.NumberFormats;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.Heap;

import java.util.function.BiFunction;

/**
 * A hardware-accelerated pair of double values with flexible semantic interpretation.
 *
 * <p>
 * {@link Pair} represents two double values stored contiguously in memory. The class provides
 * multiple accessor methods with different naming conventions, allowing the same data structure
 * to be interpreted in various contexts:
 * </p>
 * <ul>
 *   <li><strong>Cartesian coordinates</strong>: x, y</li>
 *   <li><strong>Generic pair</strong>: a, b or left, right or _1, _2</li>
 *   <li><strong>Angular coordinates</strong>: theta, phi</li>
 *   <li><strong>Complex numbers</strong>: r (real), i (imaginary)</li>
 * </ul>
 *
 * <h2>Memory Layout</h2>
 * <p>
 * A Pair occupies 2 memory positions:
 * </p>
 * <ul>
 *   <li><strong>Position 0</strong>: First value (x, a, left, theta, r, _1)</li>
 *   <li><strong>Position 1</strong>: Second value (y, b, right, phi, i, _2)</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Creating Pairs</h3>
 * <pre>{@code
 * // 2D coordinates
 * Pair<Pair> point = new Pair(3.0, 4.0);
 * double x = point.getX();  // 3.0
 * double y = point.getY();  // 4.0
 *
 * // Generic pair
 * Pair<Pair> pair = new Pair();
 * pair.setA(1.0).setB(2.0);
 *
 * // Angular coordinates
 * Pair<Pair> angles = new Pair();
 * angles.setTheta(Math.PI/4).setPhi(Math.PI/6);
 * }</pre>
 *
 * <h3>Multiple Naming Conventions</h3>
 * <pre>{@code
 * Pair<Pair> p = new Pair(5.0, 10.0);
 *
 * // All of these access the same first value
 * double v1 = p.getX();      // 5.0
 * double v2 = p.getA();      // 5.0
 * double v3 = p.getLeft();   // 5.0
 * double v4 = p.x();         // 5.0 (short form)
 *
 * // All of these access the same second value
 * double v5 = p.getY();      // 10.0
 * double v6 = p.getB();      // 10.0
 * double v7 = p.getRight();  // 10.0
 * double v8 = p.y();         // 10.0 (short form)
 * }</pre>
 *
 * <h3>Creating Pair Collections</h3>
 * <pre>{@code
 * // Bank of 100 pairs
 * PackedCollection<Pair<?>> pairs = Pair.bank(100);
 *
 * // Table of pairs (2D array)
 * PackedCollection<PackedCollection<Pair<?>>> table = Pair.table(10, 20);
 * }</pre>
 *
 * @param <T>  the type parameter extending {@link PackedCollection}
 * @see PackedCollection
 * @see MemoryData
 */
public class Pair<T extends PackedCollection> extends PackedCollection<T> {
	/**
	 * Constructs a new {@link Pair} with both values initialized to zero.
	 */
	public Pair() {
		super(2);
	}

	/**
	 * Constructs a new {@link Pair} backed by the specified {@link MemoryData}.
	 *
	 * @param delegate  the memory data to use as backing storage
	 */
	public Pair(MemoryData delegate) {
		this(delegate, 0);
	}

	/**
	 * Constructs a new {@link Pair} backed by the specified {@link MemoryData} at the given offset.
	 * This constructor is used internally for creating pairs within {@link PackedCollection}s.
	 *
	 * @param delegate        the memory data to use as backing storage
	 * @param delegateOffset  the offset within the delegate where this pair's data begins
	 */
	public Pair(MemoryData delegate, int delegateOffset) {
		super(new TraversalPolicy(2), 0, delegate, delegateOffset);
	}

	/**
	 * Constructs a new {@link Pair} with the specified values.
	 *
	 * @param x  the first value
	 * @param y  the second value
	 */
	public Pair(double x, double y) {
		this();
		this.setMem(new double[] { x, y });
	}
	
	/**
	 * Sets the first value (X coordinate).
	 *
	 * @param x  the new first value
	 * @return this {@link Pair} for method chaining
	 */
	public Pair setX(double x) {
		setMem(0, x);
		return this;
	}

	/**
	 * Sets the second value (Y coordinate).
	 *
	 * @param y  the new second value
	 * @return this {@link Pair} for method chaining
	 */
	public Pair setY(double y) {
		setMem(1, y);
		return this;
	}

	/** Sets the first value (A). Alias for {@link #setX(double)}. @param a the new first value @return this pair */
	public Pair setA(double a) { this.setX(a); return this; }

	/** Sets the second value (B). Alias for {@link #setY(double)}. @param b the new second value @return this pair */
	public Pair setB(double b) { this.setY(b); return this; }

	/** Sets the first value (left). Alias for {@link #setX(double)}. @param l the new first value @return this pair */
	public Pair setLeft(double l) { this.setX(l); return this; }

	/** Sets the second value (right). Alias for {@link #setY(double)}. @param r the new second value @return this pair */
	public Pair setRight(double r) { this.setY(r); return this; }

	/** Sets the first value (theta). Alias for {@link #setX(double)}. @param t the new first value @return this pair */
	public Pair setTheta(double t) { this.setX(t); return this; }

	/** Sets the second value (phi). Alias for {@link #setY(double)}. @param p the new second value @return this pair */
	public Pair setPhi(double p) { this.setY(p); return this; }

	/**
	 * Returns the first value (X coordinate).
	 *
	 * @return the first value
	 */
	public double getX() {
		return getMem().toArray(getOffset(), 2)[0];
	}

	/**
	 * Returns the second value (Y coordinate).
	 *
	 * @return the second value
	 */
	public double getY() {
		return getMem().toArray(getOffset(), 2)[1];
	}

	/** Returns the first value (A). Alias for {@link #getX()}. @return the first value */
	public double getA() { return getX(); }

	/** Returns the second value (B). Alias for {@link #getY()}. @return the second value */
	public double getB() { return getY(); }

	/** Returns the first value (left). Alias for {@link #getX()}. @return the first value */
	public double getLeft() { return getX(); }

	/** Returns the second value (right). Alias for {@link #getY()}. @return the second value */
	public double getRight() { return getY(); }

	/** Returns the first value (theta angle). Alias for {@link #getX()}. @return the first value */
	public double getTheta() { return getX(); }

	/** Returns the second value (phi angle). Alias for {@link #getY()}. @return the second value */
	public double getPhi() { return getY(); }

	/** Short form accessor for the first value. Alias for {@link #getX()}. @return the first value */
	public double x() { return getX(); }

	/** Short form accessor for the second value. Alias for {@link #getY()}. @return the second value */
	public double y() { return getY(); }

	/** Short form accessor for the first value. Alias for {@link #getX()}. @return the first value */
	public double a() { return getX(); }

	/** Short form accessor for the second value. Alias for {@link #getY()}. @return the second value */
	public double b() { return getY(); }

	/** Short form accessor for the first value. Alias for {@link #getX()}. @return the first value */
	public double left() { return getX(); }

	/** Short form accessor for the second value. Alias for {@link #getY()}. @return the second value */
	public double right() { return getY(); }

	/** Short form accessor for the first value (theta). Alias for {@link #getX()}. @return the first value */
	public double theta() { return getX(); }

	/** Short form accessor for the second value (phi). Alias for {@link #getY()}. @return the second value */
	public double phi() { return getY(); }

	/** Returns the first value (real part for complex numbers). Alias for {@link #getX()}. @return the first value */
	public double r() { return getX(); }

	/** Returns the second value (imaginary part for complex numbers). Alias for {@link #getY()}. @return the second value */
	public double i() { return getY(); }

	/** Returns the first value (position 1). Alias for {@link #getX()}. @return the first value */
	public double _1() { return getX(); }

	/** Returns the second value (position 2). Alias for {@link #getY()}. @return the second value */
	public double _2() { return getY(); }

	/**
	 * Returns an integer hash code value for this {@link Pair}.
	 * The hash code is computed by summing both values and casting to int.
	 *
	 * <p>
	 * Note: This hash code implementation may produce collisions for pairs with
	 * different values that sum to the same total.
	 * </p>
	 *
	 * @return the hash code value
	 */
	@Override
	public int hashCode() {
		return (int) (this.getX() + this.getY());
	}

	/**
	 * Tests whether this {@link Pair} is equal to the specified object.
	 * Returns true if and only if the specified object is a {@link Pair}
	 * with identical first and second values.
	 *
	 * @param obj  the object to compare with
	 * @return true if the pairs are equal, false otherwise
	 */
	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Pair == false)
			return false;

		Pair pair = (Pair) obj;
		return pair.getX() == this.getX() && pair.getY() == this.getY();
	}

	/**
	 * Returns the memory length required for a {@link Pair}.
	 *
	 * @return 2 (for the two values)
	 */
	@Override
	public int getMemLength() {
		return 2;
	}

	/**
	 * Returns the default memory delegate for {@link Pair} instances.
	 *
	 * @return the default {@link Heap}
	 */
	@Override
	public Heap getDefaultDelegate() { return Heap.getDefault(); }

	/**
	 * Returns a detailed string representation of this {@link Pair} including shape information.
	 *
	 * @return a string in the format "shape [x, y]"
	 */
	@Override
	public String describe() {
		return getShape() +
				" [" +
				NumberFormats.formatNumber(getX()) +
				", " +
				NumberFormats.formatNumber(getY()) +
				"]";
	}

	/**
	 * Returns a string representation of this {@link Pair}.
	 *
	 * @return a string in the format "[x, y]"
	 */
	@Override
	public String toString() {
		return "[" +
				NumberFormats.formatNumber(getX()) +
				", " +
				NumberFormats.formatNumber(getY()) +
				"]";
	}

	/**
	 * Returns the standard {@link TraversalPolicy} for a {@link Pair}.
	 * A pair has a shape of [2], representing two values.
	 *
	 * @return the traversal policy for pairs
	 */
	public static TraversalPolicy shape() {
		return new TraversalPolicy(2);
	}

	/**
	 * Creates a {@link PackedCollection} containing the specified number of {@link Pair}s.
	 * Each pair in the bank can be accessed and modified independently.
	 *
	 * <pre>{@code
	 * PackedCollection<Pair<?>> pairs = Pair.bank(100);
	 * pairs.get(0).setX(1.0).setY(2.0);
	 * }</pre>
	 *
	 * @param count  the number of pairs to allocate
	 * @return a packed collection of pairs
	 */
	public static PackedCollection<Pair<?>> bank(int count) {
		return new PackedCollection<>(new TraversalPolicy(count, 2), 1, delegateSpec ->
				new Pair<>(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	/**
	 * Creates a {@link PackedCollection} of {@link Pair}s backed by the specified {@link MemoryData}.
	 *
	 * @param count           the number of pairs to allocate
	 * @param delegate        the memory data to use as backing storage
	 * @param delegateOffset  the offset within the delegate where the pair bank begins
	 * @return a packed collection of pairs
	 */
	public static PackedCollection<Pair<?>> bank(int count, MemoryData delegate, int delegateOffset) {
		return new PackedCollection<>(new TraversalPolicy(count, 2), 1, delegateSpec ->
				new Pair<>(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}

	/**
	 * Creates a 2D table of {@link Pair}s as a {@link PackedCollection} of {@link PackedCollection}s.
	 * This creates a matrix-like structure where each row contains multiple pairs.
	 *
	 * @param width  the number of pairs in each row
	 * @param count  the number of rows
	 * @return a 2D collection of pairs
	 */
	public static PackedCollection<PackedCollection<Pair<?>>> table(int width, int count) {
		return new PackedCollection<>(new TraversalPolicy(count, width, 2), 1, delegateSpec ->
				Pair.bank(width, delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	/**
	 * Creates a 2D table of {@link Pair}s backed by the specified {@link MemoryData}.
	 *
	 * @param width           the number of pairs in each row
	 * @param count           the number of rows
	 * @param delegate        the memory data to use as backing storage
	 * @param delegateOffset  the offset within the delegate where the table begins
	 * @return a 2D collection of pairs
	 */
	public static PackedCollection<PackedCollection<Pair<?>>> table(int width, int count, MemoryData delegate, int delegateOffset) {
		return new PackedCollection<>(new TraversalPolicy(count, width, 2), 1, delegateSpec ->
				Pair.bank(width, delegateSpec.getDelegate(), delegateSpec.getOffset()), delegate, delegateOffset);
	}

	/**
	 * Returns a postprocessor function that creates {@link Pair}s from {@link MemoryData}.
	 * Used internally by the computation framework to wrap output memory in Pair objects.
	 *
	 * @return a function that creates pairs from memory data and offset
	 */
	public static BiFunction<MemoryData, Integer, Pair<?>> postprocessor() {
		return (delegate, offset) -> new Pair<>(delegate, offset);
	}

	/**
	 * Returns a postprocessor function that creates {@link PackedCollection}s of {@link Pair}s
	 * from {@link MemoryData}. Used internally by the computation framework.
	 *
	 * @return a function that creates pair banks from memory data
	 */
	public static BiFunction<MemoryData, Integer, PackedCollection<Pair<?>>> bankPostprocessor() {
		return (output, offset) -> {
			TraversalPolicy shape = ((PackedCollection) output).getShape();
			return Pair.bank(shape.getTotalSize() / 2, output, offset);
		};
	}
}
