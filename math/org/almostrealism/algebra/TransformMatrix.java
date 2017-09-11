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

/**
 * A {@link TransformMatrix} object represents a 4 X 4 matrix used for transforming vectors.
 * A {@link TransformMatrix} object stores 16 double values for the matrix data and provides
 * methods for transforming varius types of vectors. The TransformMatrix class also provides
 * some static methods that generate certain useful matrices.
 */
public class TransformMatrix implements TripleFunction<Vector> {
  public static final int TRANSFORM_AS_LOCATION = 1;
  public static final int TRANSFORM_AS_OFFSET = 2;
  public static final int TRANSFORM_AS_NORMAL = 4;
  
  /**
    The data for the identity matrix.
  */
  
  public static final double identity[][] = {{1.0, 0.0, 0.0, 0.0},
											{0.0, 1.0, 0.0, 0.0},
											{0.0, 0.0, 1.0, 0.0},
											{0.0, 0.0, 0.0, 1.0}};
  
  private double matrix[][];
  private double inverse[][];
  private double inverseT[][];
  private TransformMatrix inverseMatrix;
  private TransformMatrix inverseTranspose;
  
  private boolean inverted, isIdentity;
  
  private double df;

	/**
	  Constructs a TransformMatrix object that by default contains the data for a 4 X 4 identity matrix.
	*/
	
	public TransformMatrix() {
		this.setMatrix(TransformMatrix.identity);
		
		this.inverse = new double[4][4];
		this.inverted = false;
		
		this.df = 0;
	}
	
	/**
	  Constructs a TransformMatrix object with the specified matrix data. Any extra array entries are removed
	  and missing array entries are replaced with 0.0.
	*/
	
	public TransformMatrix(double matrix[][]) { 
		this.setMatrix(matrix);
		
		this.inverse = new double[4][4];
		this.inverted = false;
		
		this.df = 0;
	}
	
	/**
	 * Sets the 16 values stored by this TransformMatrix to those specified.
	 * Any extra array entries are removed and missing array entries are
	 * replaced with 0.0.
	 */
	public void setMatrix(double matrix[][]) {
		double newMatrix[][] = new double[4][4];
		
		boolean id = true;
		
		for(int i = 0; i < matrix.length && i < 4; i++) {
			for(int j = 0; j < matrix.length && j < 4; j++) {
				newMatrix[i][j] = matrix[i][j];
				if (newMatrix[i][j] != TransformMatrix.identity[i][j]) id = false;
			}
		}
		
		if (matrix.length < 4 || matrix[0].length < 4) id = false;
		
		this.matrix = newMatrix;
		
		this.inverted = false;
		this.isIdentity = id;
	}
	
	/**
	 * @return  The 16 values stored by this TransformMatrix as a 4 X 4 double array.
	 */
	public double[][] getMatrix() { return this.matrix; }
	
	public double[][] getInverseTransposeMatrix() {
		if (!this.inverted) this.calculateInverse();
		return this.inverseT;
	}
	
	/**
	 * Multiplys the matrix represented by this TransformMatrix object with the specified
	 * double value and returns the result as a TransformMatrix object.
	 */
	public TransformMatrix multiply(double value) {
		double newMatrix[][] = new double[4][4];
		
		for(int i = 0; i < this.matrix.length && i < 4; i++) {
			for(int j = 0; j < this.matrix.length && j < 4; j++) {
				newMatrix[i][j] = this.matrix[i][j] * value;
			}
		}
		
		return new TransformMatrix(newMatrix);
	}
	
	/**
	 * Multiplys the matrix represented by this TransformMatrix object with the matrix
	 * represented by the specified TransformMatrix object and returns the result as a
	 * TransformMatrix object.
	 */
	public TransformMatrix multiply(TransformMatrix matrix) {
		double product[][] = new double[4][4];
		
		for (int i = 0; i < product.length; i++) {
			for (int j = 0; j < product[i].length; j++) {
				product[j][i] = this.matrix[j][0] * matrix.getMatrix()[0][i] +
						this.matrix[j][1] * matrix.getMatrix()[1][i] +
						this.matrix[j][2] * matrix.getMatrix()[2][i] +
						this.matrix[j][3] * matrix.getMatrix()[3][i];
			}
		}
		
		return new TransformMatrix(product);
	}
	
	/**
	 * Delegates to {@link #transformAsOffset(Vector)}.
	 */
	@Override
	public Vector operate(Triple in) {
		return transformAsOffset(new Vector(in.getA(), in.getB(), in.getC()));
	}
	
	public void transform(Vector vector, int type) {
		if (this.isIdentity) return;
		
		if (type == TransformMatrix.TRANSFORM_AS_LOCATION) {
			vector.setX(this.matrix[0][0] * vector.getX() + this.matrix[0][1] * vector.getY() + this.matrix[0][2] * vector.getZ() + this.matrix[0][3]);
			vector.setY(this.matrix[1][0] * vector.getX() + this.matrix[1][1] * vector.getY() + this.matrix[1][2] * vector.getZ() + this.matrix[1][3]);
			vector.setZ(this.matrix[2][0] * vector.getX() + this.matrix[2][1] * vector.getY() + this.matrix[2][2] * vector.getZ() + this.matrix[2][3]);
		} else if (type == TransformMatrix.TRANSFORM_AS_OFFSET) {
			vector.setX(this.matrix[0][0] * vector.getX() + this.matrix[0][1] * vector.getY() + this.matrix[0][2] * vector.getZ());
			vector.setY(this.matrix[1][0] * vector.getX() + this.matrix[1][1] * vector.getY() + this.matrix[1][2] * vector.getZ());
			vector.setZ(this.matrix[2][0] * vector.getX() + this.matrix[2][1] * vector.getY() + this.matrix[2][2] * vector.getZ());
		} else if (type == TransformMatrix.TRANSFORM_AS_NORMAL) {
			if (!this.inverted) this.calculateInverse();
			this.inverseTranspose.transform(vector, TransformMatrix.TRANSFORM_AS_OFFSET);
		} else {
			throw new IllegalArgumentException("Illegal type: " + type);
		}
	}
	
	public double[] transform(double x, double y, double z, int type) {
		if (this.isIdentity) return new double[] {x, y, z};
		
		double d[] = new double[3];
		
		if (type == TransformMatrix.TRANSFORM_AS_LOCATION) {
			d[0] = this.matrix[0][0] * x + this.matrix[0][1] * y + this.matrix[0][2] * z + this.matrix[0][3];
			d[1] = this.matrix[1][0] * x + this.matrix[1][1] * y + this.matrix[1][2] * z + this.matrix[1][3];
			d[2] = this.matrix[2][0] * x + this.matrix[2][1] * y + this.matrix[2][2] * z + this.matrix[2][3];
		} else if (type == TransformMatrix.TRANSFORM_AS_OFFSET) {
			d[0] = this.matrix[0][0] * x + this.matrix[0][1] * y + this.matrix[0][2] * z;
			d[1] = this.matrix[1][0] * x + this.matrix[1][1] * y + this.matrix[1][2] * z;
			d[2] = this.matrix[2][0] * x + this.matrix[2][1] * y + this.matrix[2][2] * z;
		} else if (type == TransformMatrix.TRANSFORM_AS_NORMAL) {
			if (!this.inverted) this.calculateInverse();
			
			d[0] = this.inverseT[0][0] * x + this.inverseT[0][1] * y + this.inverseT[0][2] * z;
			d[1] = this.inverseT[1][0] * x + this.inverseT[1][1] * y + this.inverseT[1][2] * z;
			d[2] = this.inverseT[2][0] * x + this.inverseT[2][1] * y + this.inverseT[2][2] * z;
		} else {
			throw new IllegalArgumentException("Illegal type: " + type);
		}
		
		return d;
	}
	
	/**
	 * Computes and returns the result of the vector multiplication of the matrix represented by this
	 * TransformMatrix object and the vector represented by the specified Vector object assuming that
	 * the specified vector describes a location on 3d space.
	 */
	public Vector transformAsLocation(Vector vector) {
		if (this.isIdentity) return (Vector) vector.clone();
		
		double x, y, z;
		
		x = this.matrix[0][0] * vector.getX() + this.matrix[0][1] * vector.getY() + this.matrix[0][2] * vector.getZ() + this.matrix[0][3];
		y = this.matrix[1][0] * vector.getX() + this.matrix[1][1] * vector.getY() + this.matrix[1][2] * vector.getZ() + this.matrix[1][3];
		z = this.matrix[2][0] * vector.getX() + this.matrix[2][1] * vector.getY() + this.matrix[2][2] * vector.getZ() + this.matrix[2][3];
		
		return new Vector(x, y, z);
	}
	
	/**
	 * Computes and returns the result of the vector multiplication of the matrix represented
	 * by this TransformMatrix object and the vector represented by the specified Vector object
	 * assuming that the specified vector describes an offset in 3d space.
	 */
	public Vector transformAsOffset(Vector vector) {
		if (this.isIdentity) return (Vector) vector.clone();
		
		double x, y, z;
		
		x = this.matrix[0][0] * vector.getX() + this.matrix[0][1] * vector.getY() + this.matrix[0][2] * vector.getZ();
		y = this.matrix[1][0] * vector.getX() + this.matrix[1][1] * vector.getY() + this.matrix[1][2] * vector.getZ();
		z = this.matrix[2][0] * vector.getX() + this.matrix[2][1] * vector.getY() + this.matrix[2][2] * vector.getZ();
		
		return new Vector(x, y, z);
	}
	
	/**
	 * Computes and returns the result of the vector multiplication of the matrix represented
	 * by this TransformMatrix object and the vector represented by the specified Vector object
	 * assuming that the specified vector describes a surface normal in 3d space.
	 */
	public Vector transformAsNormal(Vector vector) {
		if (this.isIdentity) return (Vector) vector.clone();
		
		if (!this.inverted) this.calculateInverse();
		return this.inverseTranspose.transformAsOffset(vector);
	}
	
	/**
	 * Calculates the inverse of the matrix represented by this TransformMatrix
	 * object and stores it for later use.
	 */
	public void calculateInverse() {
		double det = 0.0;
		
		if (this.isIdentity) {
			this.inverse = new double[][]
		                   {{1, 0, 0, 0},
							{0, 1, 0, 0},
							{0, 0, 1, 0},
							{0, 0, 0, 1}};
		} else {
			det = this.determinant();
		}
		
		if (det != 0.0) {
			this.inverse = this.adjoint().getMatrix();
			this.inverseMatrix = (new TransformMatrix(this.inverse)).multiply(1.0 / det);
		} else {
			this.inverseMatrix = new TransformMatrix(TransformMatrix.identity);
		}
		
		this.inverse = this.inverseMatrix.getMatrix();
		this.inverseTranspose = this.inverseMatrix.transpose();
		this.inverseT = this.inverseTranspose.getMatrix();
		
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
	
	/**
	 * Computes the determinant of the matrix represented by this TransformMatrix object and
	 * returns the result as a double value.
	 */
	public double determinant() {
		double det = 1.0;
		
		double newMatrix[][] = this.toUpperTriangle().getMatrix();
		
		for (int i = 0; i < newMatrix.length; i++) {
			det = det * newMatrix[i][i];
		}
		
		det = det * this.df;
		
		return det;
	}
	
	/**
	 * Computes the transpose of the matrix represented by this TransformMatrix object and
	 * returns the result as a TransformMatrix object.
	 */
	public TransformMatrix transpose() {
		double transpose[][] = new double[this.matrix.length][this.matrix[0].length];
		
		for (int i = 0; i < transpose.length; i++) {
			for (int j = 0; j < transpose.length; j++) {
				transpose[i][j] = this.matrix[j][i];
			}
		}
		
		return new TransformMatrix(transpose);
	}
	
	/**
	 * Computes the adjoint of the matrix represented by this TransformMatrix object and
	 * returns the result as a TransformMatrix object.
	 */
	public TransformMatrix adjoint() {
		double adjoint[][] = new double[this.matrix.length][this.matrix[0].length];
		int ii, jj, ia, ja;
		double det;
		
		for (int i = 0; i < adjoint.length; i++) {
			for (int j = 0; j < adjoint[i].length; j++) {
				ia = ja = 0;
				
				double ap[][] = (new TransformMatrix(TransformMatrix.identity)).getMatrix();
				
				for (ii = 0; ii < adjoint.length; ii++) {
					for (jj = 0; jj < adjoint[i].length; jj++) {
						if ((ii != i) && (jj != j)) {
							ap[ia][ja] = this.matrix[ii][jj];
							ja++;
						}
					}
					
					if ((ii != i ) && (jj != j)) { ia++; }
					ja = 0;
				}
				
				det = (new TransformMatrix(ap)).determinant();
				adjoint[i][j] = Math.pow(-1, i + j) * det;
			}
		}
		
		TransformMatrix adjointMatrix = new TransformMatrix(adjoint);
		adjointMatrix = adjointMatrix.transpose();
		
		return adjointMatrix;
	}
	
	/**
	 * Converts the matrix represented by this TransformMatrix object to an upper triangle matrix and
	 * returns the result as a TransformMatrix object.
	 */
	public TransformMatrix toUpperTriangle() {
		double newMatrix[][] = (new TransformMatrix(this.matrix)).getMatrix();
		
		double f1 = 0;
		double temp = 0;
		int v = 1;
		
		this.df = 1;
		
		for (int col = 0; col < newMatrix.length - 1; col++) {
			for (int row = col + 1; row < newMatrix.length; row++) {
				v = 1;
				
				w: while(newMatrix[col][col] == 0) {
					if (col + v >= newMatrix.length) {
						this.df = 0;
						break w;
					} else {
						for(int c = 0; c < newMatrix.length; c++) {
							temp = newMatrix[col][c];
							newMatrix[col][c] = newMatrix[col + v][c];
							newMatrix[col + v][c] = temp;
						}
						
						v++;
						this.df = df * -1;
					}
				}
				
				if (newMatrix[col][col] != 0) {
					try {
						f1 = (-1) * newMatrix[row][col] / newMatrix[col][col];
						
						for (int i = col; i < newMatrix.length; i++) {
							newMatrix[row][i] = f1 * newMatrix[col][i] + newMatrix[row][i];
						}
					} catch(Exception e) {
					}
				}
			}
		}
		
		return new TransformMatrix(newMatrix);
	}
	
	/**
	 * @return  A String representation of the data stored by this TransformMatrix object.
	 */
	public String toString() {
		String data = "[ " + this.matrix[0][0] + ", " + this.matrix[0][1] + ", " + this.matrix[0][2] + ", " + this.matrix[0][3] + " ]\n" +
				"[ " + this.matrix[1][0] + ", " + this.matrix[1][1] + ", " + this.matrix[1][2] + ", " + this.matrix[1][3] + " ]\n" +
				"[ " + this.matrix[2][0] + ", " + this.matrix[2][1] + ", " + this.matrix[2][2] + ", " + this.matrix[2][3] + " ]\n" +
				"[ " + this.matrix[3][0] + ", " + this.matrix[3][1] + ", " + this.matrix[3][2] + ", " + this.matrix[3][3] + " ]";
		
		return data;
	}
	
	/**
	 * Generates a TransformMatrix object that can be used to translate vectors using the specified
	 * translation coordinates.
	 */
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
	 * Generates a TransformMatrix object that can be used to scale vectors using the specified scaling
	 * coefficients.
	 */
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
}
