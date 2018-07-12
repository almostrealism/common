/*
 * Copyright 2018 Michael Murray
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


import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.math.Hardware;
import org.almostrealism.math.MemWrapper;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_mem;

/**
 * A {@link Ray} represents a 3d ray. It stores the origin and direction of a 3d ray,
 * which are vector quantities, as {@link Vector}s.
 * 
 * @author  Michael Murray
 */
public class Ray implements MemWrapper, Cloneable {
	private cl_mem mem;

	private Ray(double coords[]) {
		this.setMem(coords);
	}
	
	/**
	 * Constructs a Ray object with origin and direction at the origin.
	 */
	public Ray() { }
	
	/**
	 * Constructs a Ray object using the specified origin and direction vectors.
	 */
	public Ray(Vector origin, Vector direction) {
		this.setOrigin(origin);
		this.setDirection(direction);
	}
	
	/**
	 * Sets the origin of this Ray object to the specified origin vector.
	 */
	public void setOrigin(Vector origin) {
		// TODO  This can be made faster
		double coords[] = toArray();
		double o[] = origin.toArray();
		coords[0] = o[0];
		coords[1] = o[1];
		coords[2] = o[2];
		setMem(coords);
	}
	
	/**
	 * Sets the direction of this Ray object to the specified direction vector.
	 */
	public void setDirection(Vector direction) {
		// TODO  This can be made faster
		double coords[] = toArray();
		double d[] = direction.toArray();
		coords[3] = d[0];
		coords[4] = d[1];
		coords[5] = d[2];
		setMem(coords);
	}
	
	/**
	 * Retrusn a transform of the origin and direction of this ray using the specified {@link TransformMatrix}.
	 * 
	 * @param tm  TransformMatrix to use.
	 * @return  {{ox, oy, oz}, {dx, dy, dz}} after transformation.
	 */
	public Ray transform(TransformMatrix tm) {
		// TODO  Hardware accelerate

		double m[][] = tm.getMatrix();

		double inCoords[] = toArray();
		double outCoords[] = new double[0];

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
	public double oDoto() {
		// TODO  Hardware accelerate
		// TODO  Cache
		// TODO  Return Producer<Scalar>
		double coords[] = toArray();
		return coords[0] * coords[0] +
				coords[1] * coords[1] +
				coords[2] * coords[2];
	}
	
	/**
	 * @return  The dot product of the direction of this ray with itself.
	 */
	public double dDotd() {
		// TODO  Hardware accelerate
		// TODO  Cache
		// TODO  Return Producer<Scalar>
		double coords[] = toArray();
		return coords[3] * coords[3] +
				coords[4] * coords[4] +
				coords[5] * coords[5];
	}
	
	/**
	 * @return  The dot product of the origin of this ray with the direction of this ray.
	 */
	public double oDotd() {
		// TODO  Hardware accelerate
		// TODO  Cache
		// TODO  Return Producer<Scalar>
		double coords[] = toArray();
		return coords[0] * coords[3] +
				coords[1] * coords[4] +
				coords[2] * coords[5];
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
	 * @return  The point on the ray represented by this Ray object at distance t from the origin
	 *          as a Vector object.
	 */
	public Vector pointAt(double t) {
		// TODO  hardware accelerate

		double coords[] = toArray();

		double px = coords[0] + coords[3] * t;
		double py = coords[1] + coords[4] * t;
		double pz = coords[2] + coords[5] * t;
		
		return new Vector(px, py, pz, Vector.CARTESIAN_COORDINATES);
	}

	@Override
	public cl_mem getMem() { return mem; }

	protected void setMem(double[] source) {
		setMem(0, source, 0, 6);
	}

	protected void setMem(double[] source, int offset) {
		setMem(0, source, offset, 6);
	}

	protected void setMem(int offset, double[] source, int srcOffset, int length) {
		Pointer src = Pointer.to(source).withByteOffset(srcOffset* Sizeof.cl_double);
		CL.clEnqueueWriteBuffer(Hardware.getLocalHardware().getQueue(), mem, CL.CL_TRUE,
				offset * Sizeof.cl_double, length * Sizeof.cl_double,
				src, 0, null, null);
	}

	protected void setMem(int offset, Ray src, int srcOffset, int length) {
		CL.clEnqueueCopyBuffer(Hardware.getLocalHardware().getQueue(), src.mem, this.mem,
				srcOffset * Sizeof.cl_double,
				offset * Sizeof.cl_double,length * Sizeof.cl_double,
				0,null,null);
	}

	protected void getMem(int sOffset, double out[], int oOffset, int length) {
		Pointer dst = Pointer.to(out).withByteOffset(oOffset * Sizeof.cl_double);
		CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
				CL.CL_TRUE, sOffset * Sizeof.cl_double,
				length * Sizeof.cl_double, dst, 0,
				null, null);
	}

	protected void getMem(double out[], int offset) { getMem(0, out, offset, 6); }

	@Override
	public Object clone() {
		// TODO  hardware accelerate
		double coords[] = toArray();
		return new Ray(coords);
	}

	public double[] toArray() {
		double coords[] = new double[6];
		getMem(coords, 0);
		return coords;
	}

	@Override
	public void finalize() {
		if (mem == null) return;
		CL.clReleaseMemObject(mem);
		mem = null;
	}

	/**
	 * @return  A String representation of this Ray object.
	 */
	@Override
	public String toString() {
		double coords[] = toArray();
		String value = "Ray: [" + coords[0] + ", " + coords[1] + ", " + coords[2] +
					"] [" + coords[3] + ", " + coords[4] + ", " + coords[5] + "]";
		
		return value;
	}
}
