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


import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.math.AcceleratedProducer;
import org.almostrealism.math.Hardware;
import org.almostrealism.math.MemWrapper;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;
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
		this();
		this.setMem(coords);
	}
	
	/**
	 * Constructs a Ray object with origin and direction at the origin.
	 */
	public Ray() {
		mem = CL.clCreateBuffer(Hardware.getLocalHardware().getContext(),
				CL.CL_MEM_READ_WRITE,6 * Sizeof.cl_double,
				null, null);
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
	public Producer<Scalar> oDoto() {
		// TODO  Cache
		return new AcceleratedProducer<>("rayODotO", false,
										new Producer[] { Scalar.blank() },
										new Object[] { this });
	}
	
	/**
	 * @return  The dot product of the direction of this ray with itself.
	 */
	public Producer<Scalar> dDotd() {
		// TODO  Cache
		return new AcceleratedProducer<>("rayDDotD", false,
										new Producer[] { Scalar.blank() },
										new Object[] { this });
	}
	
	/**
	 * @return  The dot product of the origin of this ray with the direction of this ray.
	 */
	public Producer<Scalar> oDotd() {
		// TODO  Cache
		return new AcceleratedProducer<>("rayODotD", false,
										new Producer[] { Scalar.blank() },
										new Object[] { this });
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
	public Producer<Vector> pointAt(Producer<Scalar> t) {
		return new RayPointAt(new StaticProducer<>(this), t);
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
		Pointer src = Pointer.to(source).withByteOffset(srcOffset * Sizeof.cl_double);
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

	protected void setMem(int offset, Vector src, int srcOffset, int length) {
		CL.clEnqueueCopyBuffer(Hardware.getLocalHardware().getQueue(), src.getMem(), this.mem,
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
	public boolean equals(Object o) {
		if (o instanceof Ray == false) return false;
		double r1[] = this.toArray();
		double r2[] = ((Ray) o).toArray();

		for (int i = 0; i < 6; i++) {
			if (r1[i] != r2[i]) return false;
		}

		return true;
	}

	@Override
	public int hashCode() { return getOrigin().hashCode(); }

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
	public void destroy() {
		if (mem == null) return;
		CL.clReleaseMemObject(mem);
		mem = null;
	}

	@Override
	public void finalize() throws Throwable {
		destroy();
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
