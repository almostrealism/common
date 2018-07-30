/*
 * Copyright 2017 Michael Murray
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

package org.almostrealism.algebra;

import io.almostrealism.code.Scope;
import org.almostrealism.math.HardwareOperator;
import org.almostrealism.math.Hardware;
import org.almostrealism.math.MemWrapper;
import org.almostrealism.relation.TripleFunction;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_mem;

/**
 * A {@link TransformMatrix} object represents a 4 X 4 matrix used for transforming vectors.
 * A {@link TransformMatrix} object stores 16 double values for the matrix data and provides
 * methods for transforming varius types of vectors. The TransformMatrix class also provides
 * some static methods that generate certain useful matrices.
 */
public class TransformMatrix implements TripleFunction<Vector>, MemWrapper {
	public static final int TRANSFORM_AS_LOCATION = 1;
	public static final int TRANSFORM_AS_OFFSET = 2;
	public static final int TRANSFORM_AS_NORMAL = 4;

  /** The data for the identity matrix. */
	public static final double identity[][] = {{1.0, 0.0, 0.0, 0.0},
											{0.0, 1.0, 0.0, 0.0},
											{0.0, 0.0, 1.0, 0.0},
											{0.0, 0.0, 0.0, 1.0}};
	private static ThreadLocal<HardwareOperator<Vector>> transformAsLocation = new ThreadLocal<>();
	private static ThreadLocal<HardwareOperator<Vector>> transformAsOffset = new ThreadLocal<>();

	private cl_mem matrix;
	private TransformMatrix inverseMatrix;
	private TransformMatrix inverseTranspose;
	private TransformMatrix transposeMatrix;

	private boolean inverted, isIdentity;

	/**
	 * Constructs a {@link TransformMatrix} that by default contains the data for a 4 X 4 identity matrix.
	 */
	public TransformMatrix() {
		this(true);
	}

	private TransformMatrix(boolean identity) {
		initMem(identity);
	}
	
	/**
	 * Constructs a TransformMatrix object with the specified matrix data. Any extra array entries are removed
	 * and missing array entries are replaced with 0.0.
	 */
	public TransformMatrix(double matrix[][]) {
		initMem(false);
		this.setMatrix(matrix);
	}

	private void initMem(boolean identity) {
		matrix = CL.clCreateBuffer(Hardware.getLocalHardware().getContext(),
				CL.CL_MEM_READ_WRITE,16 * Sizeof.cl_double,
				null, null);
		if (identity) {
			new IdentityMatrix(new StaticProducer<>(this)).evaluate(new Object[0]);
		}
	}
	
	/**
	 * Sets the 16 values stored by this TransformMatrix to those specified.
	 * Any extra array entries are removed and missing array entries are
	 * replaced with 0.0.
	 */
	public void setMatrix(double matrix[][]) {
		double newMatrix[] = new double[16];
		
		boolean id = true;
		
		for(int i = 0; i < matrix.length && i < 4; i++) {
			for(int j = 0; j < matrix.length && j < 4; j++) {
				int index = i * 4 + j;
				newMatrix[index] = matrix[i][j];
				if (newMatrix[index] != TransformMatrix.identity[i][j]) id = false;
			}
		}
		
		if (matrix.length < 4 || matrix[0].length < 4) id = false;
		
		this.setMem(newMatrix);
		
		this.inverted = false;
		this.isIdentity = id;
	}
	
	/**
	 * This method is slow.
	 *
	 * @return  The 16 values stored by this TransformMatrix as a 4 X 4 double array.
	 */
	@Deprecated
	public double[][] getMatrix() {
		double m[] = toArray();
		return new double[][] { { m[0],  m[1],  m[2],  m[3] },
								{ m[4],  m[5],  m[6],  m[7] },
								{ m[8],  m[9],  m[10], m[11] },
								{ m[12], m[13], m[14], m[15] } };
	}

	/**
	 * This method is slow.
	 *
	 * @param r  Matrix row.
	 * @param c  Matrix column.
	 *
	 * @return  Value from matrix.
	 */
	@Deprecated
	public double getValue(int r, int c) { return this.toArray()[r * r + c]; }
	
	public TransformMatrix getInverseTransposeMatrix() {
		if (!this.inverted) this.calculateInverse();
		return this.inverseMatrix;
	}
	
	/**
	 * Multiplys the matrix represented by this TransformMatrix object with the specified
	 * double value and returns the result as a TransformMatrix object.
	 */
	// TODO  Improve the performance of this method using CL
	public TransformMatrix multiply(double value) {
		double m[] = toArray();
		double newMatrix[][] = new double[4][4];
		
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				newMatrix[i][j] = m[i * 4 + j] * value;
			}
		}
		
		return new TransformMatrix(newMatrix);
	}
	
	/**
	 * Multiplies the matrix represented by this {@link TransformMatrix} with the matrix
	 * represented by the specified {@link TransformMatrix} and returns the result as a
	 * {@link TransformMatrix}.
	 *
	 * @see  MatrixProduct
	 */
	public TransformMatrix multiply(TransformMatrix matrix) {
		return new MatrixProduct(new StaticProducer<>(this), new StaticProducer<>(matrix)).evaluate(new Object[0]);
	}
	
	/**
	 * Delegates to {@link #transformAsOffset(Vector)}.
	 */
	@Override
	public Vector operate(Triple in) {
		return transformAsOffset(new Vector(in.getA(), in.getB(), in.getC()));
	}

	@Override
	public Scope getScope(String prefix) {
		throw new RuntimeException("getScope is not implemented"); // TODO
	}

	public void transform(Vector vector, int type) {
		if (this.isIdentity) return;
		
		if (type == TransformMatrix.TRANSFORM_AS_LOCATION) {
			if (transformAsLocation.get() == null) {
				transformAsLocation.set(Hardware.getLocalHardware().getFunctions().getOperators().get("transformAsLocation", false, 2));
			}

			transformAsLocation.get().evaluate(new Object[] { vector, this });
		} else if (type == TransformMatrix.TRANSFORM_AS_OFFSET) {
			if (transformAsOffset.get() == null) {
				transformAsOffset.set(Hardware.getLocalHardware().getFunctions().getOperators().get("transformAsOffset", false, 2));
			}

			transformAsOffset.get().evaluate(new Object[] { vector, this });
		} else if (type == TransformMatrix.TRANSFORM_AS_NORMAL) {
			if (!this.inverted) this.calculateInverse();
			this.inverseTranspose.transform(vector, TransformMatrix.TRANSFORM_AS_OFFSET);
		} else {
			throw new IllegalArgumentException("Illegal type: " + type);
		}
	}
	
	public double[] transform(double x, double y, double z, int type) {
		if (this.isIdentity) return new double[] {x, y, z};

		Vector v = new Vector(x, y, z);
		transform(v, type);
		return v.toArray();
	}
	
	/**
	 * Computes and returns the result of the vector multiplication of the matrix represented by this
	 * TransformMatrix object and the vector represented by the specified Vector object assuming that
	 * the specified vector describes a location on 3d space.
	 */
	public Vector transformAsLocation(Vector vector) {
		vector = (Vector) vector.clone();
		if (this.isIdentity) return vector;

		transform(vector, TRANSFORM_AS_LOCATION);
		return vector;
	}
	
	/**
	 * Computes and returns the result of the vector multiplication of the matrix represented
	 * by this TransformMatrix object and the vector represented by the specified Vector object
	 * assuming that the specified vector describes an offset in 3d space.
	 */
	public Vector transformAsOffset(Vector vector) {
		vector = (Vector) vector.clone();
		if (this.isIdentity) return vector;

		transform(vector, TRANSFORM_AS_OFFSET);
		return vector;
	}
	
	/**
	 * Computes and returns the result of the vector multiplication of the matrix represented
	 * by this TransformMatrix object and the vector represented by the specified Vector object
	 * assuming that the specified vector describes a surface normal in 3d space.
	 */
	public Vector transformAsNormal(Vector vector) {
		vector = (Vector) vector.clone();
		if (this.isIdentity) return vector;

		transform(vector, TRANSFORM_AS_NORMAL);
		return vector;
	}
	
	/**
	 * Calculates the inverse of the matrix represented by this TransformMatrix
	 * object and stores it for later use.
	 */
	public void calculateInverse() {
		double det = 0.0;
		
		if (this.isIdentity) {
			this.inverseMatrix = new TransformMatrix();
		} else {
			det = this.determinant();
		}

		if (det == 1.0) {
			this.inverseMatrix = this.adjoint();
		} else if (det != 0.0) {
			this.inverseMatrix = this.adjoint().multiply(1.0 / det);
		} else if (!isIdentity) {
			this.inverseMatrix = new TransformMatrix(TransformMatrix.identity);
		}

		this.inverseTranspose = this.inverseMatrix.transpose();
		
		this.inverted = true;
	}
	
	/**
	 * @return  The inverse of the matrix represented by this TransformMatrix object as a
	 *          TransformMatrix object. If this method, or the calulateInverse() method,
	 *          has been called after the last matrix modification this method will return
	 *          a stored inverse.
	 */
	public TransformMatrix getInverse() {
		if (this.inverted == false)
			this.calculateInverse();
		
		return this.inverseMatrix;
	}

	public void rigidInversion() {
		throw new RuntimeException("TODO  Implement rigidInversion with CL");
		/*
		double t = matrix[0][1];
		matrix[0][1] = matrix[1][0];
		matrix[1][0] = t;

		t = matrix[0][2];
		matrix[0][2] = matrix[2][0];
		matrix[2][0] = t;

		t = matrix[1][2];
		matrix[1][2] = matrix[2][1];
		matrix[2][1] = t;

		Vector negTrans = new Vector(-matrix[0][3], -matrix[1][3], -matrix[2][3]);
		Vector trans = transformAsOffset(negTrans);
		matrix[0][3] = trans.getX();
		matrix[1][3] = trans.getY();
		matrix[2][3] = trans.getZ();
		*/
	}
	
	/**
	 * Computes the determinant of the matrix represented by this TransformMatrix object and
	 * returns the result as a double value.
	 */
	public double determinant() {
		return new MatrixDeterminant(new StaticProducer<>(this)).evaluate(new Object[0]).getValue();
	}

	/**
	 * Computes the transpose of the matrix represented by this TransformMatrix object and
	 * returns the result as a TransformMatrix object. If this method is called after the
	 * last matrix modification it will return a stored transposition.
	 */
	public TransformMatrix transpose() {
		if (transposeMatrix == null) {
			transposeMatrix = new MatrixTranspose(new StaticProducer<>(this)).evaluate(new Object[0]);
		}

		return transposeMatrix;
	}
	
	/**
	 * Computes the adjoint of the matrix represented by this TransformMatrix object and
	 * returns the result as a TransformMatrix object.
	 */
	public TransformMatrix adjoint() {
		return new MatrixAdjoint(new StaticProducer<>(this)).evaluate(new Object[0]);
	}
	
	/**
	 * Converts the matrix represented by this TransformMatrix object to an upper triangle matrix and
	 * returns the result as a TransformMatrix object.
	 */
	public TransformMatrix toUpperTriangle() {
		return new MatrixToUpperTriangle(new StaticProducer<>(this)).evaluate(new Object[0]);
	}

	@Override
	public TransformMatrix clone() {
		// TODO  Improve performance, transfer inverse matrices, etc.
		return new TransformMatrix(getMatrix());
	}

	@Override
	public cl_mem getMem() { return matrix; }

	@Override
	public void destroy() {
		if (matrix == null) return;
		CL.clReleaseMemObject(matrix);
		matrix = null;
	}

	@Override
	public void finalize() throws Throwable {
		destroy();
	}

	protected void setMem(double[] source) {
		setMem(0, source, 0, 16);
	}

	protected void setMem(double[] source, int offset) {
		setMem(0, source, offset, 16);
	}

	protected void setMem(int offset, double[] source, int srcOffset, int length) {
		Pointer src = Pointer.to(source).withByteOffset(srcOffset*Sizeof.cl_double);
		CL.clEnqueueWriteBuffer(Hardware.getLocalHardware().getQueue(), matrix, CL.CL_TRUE,
				offset * Sizeof.cl_double, length * Sizeof.cl_double,
				src, 0, null, null);
	}

	protected void setMem(int offset, TransformMatrix src, int srcOffset, int length) {
		CL.clEnqueueCopyBuffer(Hardware.getLocalHardware().getQueue(), src.matrix, matrix,
				srcOffset * Sizeof.cl_double,
				offset * Sizeof.cl_double,length * Sizeof.cl_double,
				0,null,null);
	}

	protected void getMem(int sOffset, double out[], int oOffset, int length, cl_mem mem) {
		Pointer dst = Pointer.to(out).withByteOffset(oOffset * Sizeof.cl_double);
		CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
				CL.CL_TRUE, sOffset * Sizeof.cl_double,
				length * Sizeof.cl_double, dst, 0,
				null, null);
	}

	protected void getMem(double out[], int offset, cl_mem mem) { getMem(0, out, offset, 3, mem); }

	public double[] toArray() {
		double m[] = new double[16];
		getMem(0, m, 0, 16, matrix);
		return m;
	}

	/**
	 * @return  A String representation of the data stored by this TransformMatrix object.
	 */
	public String toString() {
		double m[][] = getMatrix();

		String data = "[ " + m[0][0] + ", " + m[0][1] + ", " + m[0][2] + ", " + m[0][3] + " ]\n" +
				"[ " + m[1][0] + ", " + m[1][1] + ", " + m[1][2] + ", " + m[1][3] + " ]\n" +
				"[ " + m[2][0] + ", " + m[2][1] + ", " + m[2][2] + ", " + m[2][3] + " ]\n" +
				"[ " + m[3][0] + ", " + m[3][1] + ", " + m[3][2] + ", " + m[3][3] + " ]";
		
		return data;
	}

	public static Producer<TransformMatrix> blank() {
		return new Producer<TransformMatrix>() {
			@Override
			public TransformMatrix evaluate(Object[] args) {
				return new TransformMatrix(false);
			}

			@Override
			public void compact() { }
		};
	}

	/**
	 * Generates a TransformMatrix object that can be used to translate vectors using the specified
	 * translation coordinates.
	 *
	 * Use {@link org.almostrealism.geometry.TranslationMatrix} instead.
	 */
	@Deprecated
	public static TransformMatrix createTranslationMatrix(double tx, double ty, double tz) {
		TransformMatrix translateTransform = new TransformMatrix();
		double translate[][] = translateTransform.getMatrix();
		
		translate[0][3] = tx;
		translate[1][3] = ty;
		translate[2][3] = tz;
		
		translateTransform.setMatrix(translate);
		return translateTransform;
	}

	/**
	 * Generates a TransformMatrix object that can be used to translate vectors using the specified
	 * translation coordinates.
	 *
	 * USE {@link org.almostrealism.geometry.TranslationMatrix} instead.
	 */
	@Deprecated
	public static TransformMatrix createTranslationMatrix(Vector t) {
		return createTranslationMatrix(t.getX(), t.getY(), t.getZ());
	}

	/**
	 * Use {@link org.almostrealism.geometry.ScaleMatrix} instead.
	 */
	@Deprecated
	public static TransformMatrix createScaleMatrix(Vector s) {
		return createScaleMatrix(s.getX(), s.getY(), s.getZ());
	}

	/**
	 * Generates a {@link TransformMatrix} that can be used to scale vectors using the specified scaling
	 * coefficients.
	 *
	 * Use {@link org.almostrealism.geometry.ScaleMatrix} instead.
	 */
	@Deprecated
	public static TransformMatrix createScaleMatrix(double sx, double sy, double sz) {
		TransformMatrix scaleTransform = new TransformMatrix();
		
		double scale[][] = scaleTransform.getMatrix();
		
		scale[0][0] = sx;
		scale[1][1] = sy;
		scale[2][2] = sz;
		
		scaleTransform.setMatrix(scale);
		return scaleTransform;
	}
	
	/**
	 * Generates a TransformMatrix object that can be used to rotate vectors counterclockwise about
	 * the x axis. The angle measurement is in radians.
	 */
	public static TransformMatrix createRotateXMatrix(double angle) {
		TransformMatrix rotateTransform = new TransformMatrix();
		double rotateX[][] = rotateTransform.getMatrix();
		
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		
		rotateX[1][1] = cos;
		rotateX[1][2] = -sin;
		rotateX[2][1] = sin;
		rotateX[2][2] = cos;
		
		rotateTransform.setMatrix(rotateX);
		return rotateTransform;
	}
	
	/**
	 * Generates a TransformMatrix object that can be used to rotate vectors counterclockwise about
	 * the y axis. The angle measurement is in radians.
	 */
	public static TransformMatrix createRotateYMatrix(double angle) {
		TransformMatrix rotateTransform = new TransformMatrix();
		double rotateY[][] = rotateTransform.getMatrix();
		
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		
		rotateY[0][0] = cos;
		rotateY[0][2] = sin;
		rotateY[2][0] = -sin;
		rotateY[2][2] = cos;
		
		rotateTransform.setMatrix(rotateY);
		return rotateTransform;
	}
	
	/**
	 * Generates a TransformMatrix object that can be used to rotate vectors counterclockwise about
	 * the z axis. The angle measurement is in radians.
	 */
	public static TransformMatrix createRotateZMatrix(double angle) {
		TransformMatrix rotateTransform = new TransformMatrix();
		double rotateZ[][] = rotateTransform.getMatrix();
		
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		
		rotateZ[0][0] = cos;
		rotateZ[0][1] = -sin;
		rotateZ[1][0] = sin;
		rotateZ[1][1] = cos;
		
		rotateTransform.setMatrix(rotateZ);
		return rotateTransform;
	}

	public static TransformMatrix createRotateMatrix(double angle, Vector v) {
		v = (Vector) v.clone();
		v.normalize();

		TransformMatrix rotateTransform = new TransformMatrix();
		double rotate[][] = rotateTransform.getMatrix();
		double c = Math.cos(angle);
		double ci = 1.0 - c;
		double s = Math.sin(angle);

		rotate[0][0] = v.getX() * v.getX() * ci + c;
		rotate[0][1] = v.getX() * v.getY() * ci - v.getZ() * s;
		rotate[0][2] = v.getX() * v.getZ() * ci + v.getY() * s;
		rotate[0][3] = 0.0;

		rotate[1][0] = v.getY() * v.getX() * ci + v.getZ() * s;
		rotate[1][1] = v.getY() * v.getY() * ci + c;
		rotate[1][2] = v.getY() * v.getZ() * ci - v.getX() * s;
		rotate[1][3] = 0.0;

		rotate[2][0] = v.getX() * v.getZ() * ci - v.getY() * s;
		rotate[2][1] = v.getY() * v.getZ() * ci + v.getX() * s;
		rotate[2][2] = v.getZ() * v.getZ() * ci + c;
		rotate[2][3] = 0.0;

		rotate[3][0] = 0.0;
		rotate[3][1] = 0.0;
		rotate[3][2] = 0.0;
		rotate[3][3] = 1.0;

		rotateTransform.setMatrix(rotate);
		return rotateTransform;
	}
}
