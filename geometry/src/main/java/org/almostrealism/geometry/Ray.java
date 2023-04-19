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

package org.almostrealism.geometry;

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducerBase;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.AcceleratedEvaluable;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * A {@link Ray} represents a 3d ray. It stores the origin and direction of a 3d ray,
 * which are vector quantities, as {@link Vector}s.
 * 
 * @author  Michael Murray
 */
public class Ray extends PackedCollection<Ray> implements RayFeatures, Cloneable {
	private Ray(double coords[]) {
		this();
		this.setMem(coords);
	}
	
	/**
	 * Constructs a Ray object with origin and direction at the origin.
	 */
	public Ray() {
		super(6);
	}

	public Ray(MemoryData delegate, int delegateOffset) {
		super(new TraversalPolicy(6), 0, delegate, delegateOffset);
	}
	
	/**
	 * Constructs a Ray object using the specified origin and direction vectors.
	 */
	public Ray(Vector origin, Vector direction) {
		this();
		this.setOrigin(origin);
		this.setDirection(direction);
	}

	public Ray(Ray source) {
		this();
		this.setMem(0, source, 0, 6);
	}

	/**
	 * Sets the origin of this {@link Ray} to the specified origin {@link Vector}.
	 */
	public void setOrigin(Vector origin) {
		setMem(0, origin, 0, 3);
	}
	
	/**
	 * Sets the direction of this {@link Ray} to the specified direction {@link Vector}.
	 */
	public void setDirection(Vector direction) {
		setMem(3, direction, 0, 3);
	}
	
	/**
	 * Returns a transform of the origin and direction of this ray using the specified {@link TransformMatrix}.
	 * 
	 * @param tm  TransformMatrix to use.
	 * @return  {{ox, oy, oz}, {dx, dy, dz}} after transformation.
	 */
	@Deprecated
	public Ray transform(TransformMatrix tm) {
		// TODO  Hardware accelerate

		double m[][] = tm.getMatrix();

		double inCoords[] = toArray();
		double outCoords[] = new double[6];

		outCoords[0] = m[0][0] * inCoords[0] + m[0][1] * inCoords[1] + m[0][2] * inCoords[2] + m[0][3];
		outCoords[1] = m[1][0] * inCoords[0] + m[1][1] * inCoords[1] + m[1][2] * inCoords[2] + m[1][3];
		outCoords[2] = m[2][0] * inCoords[0] + m[2][1] * inCoords[1] + m[2][2] * inCoords[2] + m[2][3];

		outCoords[3] = m[0][0] * inCoords[3] + m[0][1] * inCoords[4] + m[0][2] * inCoords[5];
		outCoords[4] = m[1][0] * inCoords[3] + m[1][1] * inCoords[4] + m[1][2] * inCoords[5];
		outCoords[5] = m[2][0] * inCoords[3] + m[2][1] * inCoords[4] + m[2][2] * inCoords[5];
		
		return new Ray(outCoords);
	}

	/**
	 * @return  The dot product of the origin of this ray with itself.
	 */
	public Evaluable<Scalar> oDoto() {
		// TODO  Cache
		return new AcceleratedEvaluable<>("rayODotO", false, Scalar.blank(),
										new Supplier[0], new Object[] { this });
	}
	
	/**
	 * @return  The dot product of the direction of this ray with itself.
	 */
	public Evaluable<Scalar> dDotd() {
		// TODO  Cache
		return new AcceleratedEvaluable<>("rayDDotD", false, Scalar.blank(),
										new Supplier[0], new Object[] { this });
	}
	
	/**
	 * @return  The dot product of the origin of this ray with the direction of this ray.
	 */
	public Evaluable<Scalar> oDotd() {
		// TODO  Cache
		return new AcceleratedEvaluable<>("rayODotD", false, Scalar.blank(),
										new Supplier[0], new Object[] { this });
	}
	
	/**
	 * @return  The origin of this Ray object as a Vector object.
	 */
	public Vector getOrigin() {
		double coords[] = toArray();
		return new Vector(coords[0], coords[1], coords[2], Vector.CARTESIAN_COORDINATES);
	}
	
	/**
	 * @return  The direction of this Ray object as a Vector object.
	 */
	public Vector getDirection() {
		double coords[] = toArray();
		return new Vector(coords[3], coords[4], coords[5], Vector.CARTESIAN_COORDINATES);
	}
	
	/**
	 * @return  The point on the ray represented by this {@link Ray} at distance t from the origin
	 *          as a {@link Vector}.
	 */
	public VectorProducerBase pointAt(Producer<Scalar> t) {
		return pointAt(v(this), t);
	}

	@Override
	public int getMemLength() { return 6; }

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Ray)) return false;
		double r1[] = this.toArray();
		double r2[] = ((Ray) o).toArray();
		return IntStream.range(0, 6).noneMatch(i -> r1[i] != r2[i]);
	}

	@Override
	public int hashCode() { return getOrigin().hashCode(); }

	@Override
	public Ray clone() {
		// TODO  copy mem directly
		double coords[] = toArray();
		return new Ray(coords);
	}

	public double[] toArray() { return getMem().toArray(getOffset(), 6); }

	/**
	 * @return  A String representation of this Ray object.
	 */
	@Override
	public String toString() {
		double coords[] = toArray();
		return "Ray: [" + coords[0] + ", " + coords[1] + ", " + coords[2] +
					"] [" + coords[3] + ", " + coords[4] + ", " + coords[5] + "]";
	}

	public static TraversalPolicy shape() {
		return new TraversalPolicy(6);
	}

	public static Producer<Ray> blank() {
		Supplier<Ray> r = Ray::new;
		IntFunction<MemoryBank<Ray>> b = Ray::bank;
		return new DynamicProducerForMemoryData<>(r, b);
	}

	public static PackedCollection<Ray> bank(int count) {
		return new PackedCollection<>(new TraversalPolicy(count, 6), 1, delegateSpec ->
				new Ray(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	@Deprecated
	public static PackedCollection<Ray> bank(int count, Supplier<Evaluable<? extends Ray>> source) {
		PackedCollection<Ray> bank = Ray.bank(count);
		for (int i = 0; i < bank.getCount(); i++) {
			bank.set(i, source.get().evaluate());
		}

		return bank;
	}

	public static PackedCollection<Ray> bank(int count, MemoryData delegate, int delegateOffset) {
		return new PackedCollection<>(new TraversalPolicy(count, 6), 1, delegateSpec ->
				new Ray(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}
}
