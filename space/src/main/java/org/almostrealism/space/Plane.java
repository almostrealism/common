/*
 * Copyright 2018 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.space;

import io.almostrealism.code.Constant;
import io.almostrealism.code.Operator;
import io.almostrealism.compute.Process;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.ParticleGroup;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.geometry.ShadableIntersection;
import org.almostrealism.geometry.TransformMatrix;

import java.util.Collection;
import java.util.Collections;

/** A {@link Plane} represents an plane in 3d space. */
public class Plane extends AbstractSurface implements ParticleGroup, RayFeatures {
	// TODO  Move these to an enum

	/** Integer code for XY plane. **/
	public static final int XY = 2;
  
	/** Integer code for XZ plane. **/
	public static final int XZ = 4;
  
	/** Integer code for YZ plane. **/
	public static final int YZ = 8;
  
	private int type;

	private Producer<PackedCollection> normal;

	/**
	 * Constructs a {@link Plane} that represents an XY plane that is black.
	 *
	 * @see Plane#XY
	 */
	public Plane() {
		super();
		this.setType(Plane.XY);
	}
	
	/**
	 * Constructs a {@link Plane} with the orientation specified by an integer code.
	 *
	 * @see  Plane#XY
	 * @see  Plane#XZ
	 * @see  Plane#YZ
	 */
	public Plane(int type) {
		super();
		this.setType(type);
	}
	
	/**
	 * Constructs a {@link Plane} with the specified orientation and the specified color.
	 */
	public Plane(int type, RGB color) {
		super(new Vector(0.0, 0.0, 0.0), 1.0, color);
		this.setType(type);
	}
	
	/**
	 * Sets the orientation of this Plane object to the orientation specified by the integer type code.
	 * 
	 * @throws IllegalArgumentException  If the specified type code is not valid.
	 */
	public void setType(int type) {
		if (type == Plane.XY || type == Plane.XZ || type == Plane.YZ)
			this.type = type;
		else
			throw new IllegalArgumentException("Illegal type code: " + type);
	}
	
	/**
	 * Returns the integer code for the orientation of this Plane object.
	 */	
	public int getType() {
		return this.type;
	}
	
	/**
	 * Returns a Vector object that represents the vector normal to this plane at
	 * the point represented by the specified Vector object.
	 */
	@Override
	public void calculateTransform() {
		boolean recalculateNormal = normal == null || !transformCurrent;

		if (!transformCurrent) super.calculateTransform();

		if (recalculateNormal) {
			Vector n;

			if (this.type == Plane.XY)
				n = new Vector(0.0, 0.0, 1.0);
			else if (this.type == Plane.XZ)
				n = new Vector(0.0, 1.0, 0.0);
			else if (this.type == Plane.YZ)
				n = new Vector(1.0, 0.0, 0.0);
			else
				n = null;

			normal = value(n); // This causes us to avoid infinite regress

			TransformMatrix m = getTransform(true);

			if (m != null) {
				normal = m.transform(normal, TransformMatrix.TRANSFORM_AS_NORMAL);
			}
		}
	}

	@Override
	public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> p) {
		calculateTransform();
		return normal;
	}
	
	/**
	 * Returns a {@link ContinuousField} representing the points along the specified
	 * {@link Ray} that intersection between the ray and this {@link Plane} occurs.
	 */
	@Override
	public ContinuousField intersectAt(Producer<?> r) {
		TransformMatrix m = getTransform(true);
		Producer<?> tr = r;
		if (m != null) tr = m.getInverse().transform(tr);

		// tr = new RayFromVectors(new RayOrigin(tr), new RayDirection(tr).normalize());

		Producer<PackedCollection> s;

		if (type == Plane.XY) {
			s = minus(z(origin(tr))).divide(z(direction(tr)));
		} else if (type == Plane.XZ) {
			s = minus(y(origin(tr))).divide(y(direction(tr)));
		} else if (type == Plane.YZ) {
			s = minus(x(origin(tr))).divide(x(direction(tr)));
		} else {
			throw new IllegalArgumentException(String.valueOf(type));
		}

		return new ShadableIntersection(this, r, s);
	}

	@Override
	public Operator<PackedCollection> expect() {
		PackedCollection zero = new PackedCollection(1);
		zero.setMem(0, 0.0);
		return new Constant<>(zero);
	}

	@Override
	public Operator<PackedCollection> get() {
		return new Operator<>() {
			@Override
			public Evaluable<PackedCollection> get() {
				return args -> {
					PackedCollection result = new PackedCollection(1);
					if (type == Plane.XY)
						result.setMem(0, getInput().get().evaluate(args).getZ());
					else if (type == Plane.XZ)
						result.setMem(0, getInput().get().evaluate(args).getY());
					else if (type == Plane.YZ)
						result.setMem(0, getInput().get().evaluate(args).getX());
					return result;
				};
			}

			@Override
			public Scope<PackedCollection> getScope(KernelStructureContext context) {
				Scope<PackedCollection> s = new Scope<>();
				// TODO  This is not correct
				// s.getVariables().add(new Variable("scalar", get().evaluate()));
				return s;
			}

			@Override
			public Collection<Process<?, ?>> getChildren() {
				return Collections.emptyList();
			}
		};
	}

	/** @see ParticleGroup#getParticleVertices() */
    public double[][] getParticleVertices() {
        if (this.type == Plane.XY) {
            return new double[][] {{10.0, 10.0, 0.0}, {10.0, -10.0, 0.0}, {-10.0, 10.0, 0.0}, {-10.0, -10.0, 0.0}};
        } else if (this.type == Plane.XZ) {
            return new double[][] {{10.0, 0.0, 10.0}, {10.0, 0.0, -10.0}, {-10.0, 0.0, 10.0}, {-10.0, 0.0, -10.0}};
        } else if (this.type == Plane.YZ) {
            return new double[][] {{0.0, 10.0, 10.0}, {0.0, 10.0, -10.0}, {0.0, -10.0, 10.0}, {0.0, -10.0, -10.0}};
        } else {
            return null;
        }
    }
}
