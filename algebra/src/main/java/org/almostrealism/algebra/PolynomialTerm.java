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

package org.almostrealism.algebra;

/**
 * Represents a single term in a multivariate polynomial function of three variables.
 *
 * <p>
 * A {@link PolynomialTerm} represents a term in the form: c . x^i . y^j . z^k,
 * where c is the coefficient and i, j, k are non-negative integer exponents.
 * This class supports:
 * </p>
 * <ul>
 *   <li>Evaluation at specific (x, y, z) points</li>
 *   <li>Partial differentiation with respect to x, y, or z</li>
 *   <li>Term multiplication</li>
 *   <li>Like-term detection for polynomial simplification</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Create term: 5x^2y^3z
 * PolynomialTerm term = new PolynomialTerm(5.0, 2, 3, 1);
 *
 * // Evaluate at (x=2, y=1, z=3)
 * double value = term.evaluate(2, 1, 3);  // 5 * 4 * 1 * 3 = 60
 *
 * // Partial derivatives
 * PolynomialTerm dx = term.getDx();  // 10x^1y^3z
 * PolynomialTerm dy = term.getDy();  // 15x^2y^2z
 * PolynomialTerm dz = term.getDz();  // 5x^2y^3
 *
 * // Multiply terms
 * PolynomialTerm other = new PolynomialTerm(2.0, 1, 0, 2);  // 2xz^2
 * PolynomialTerm product = term.multiply(other);  // 10x^3y^3z^3
 * }</pre>
 *
 * @author  Michael Murray
 */
public class PolynomialTerm {
	private double coefficient;
  	private int expOfX, expOfY, expOfZ;
  	
  	private PolynomialTerm dx, dy, dz;

	/**
	 * Constructs a new PolynomialTerm object with a coefficient of 0.0 and all powers set to 0.
	 */
	public PolynomialTerm() {
		this.setCoefficient(0.0);
		
		this.setExpOfX(0);
		this.setExpOfY(0);
		this.setExpOfZ(0);
	}
	
	/**
	 * Constructs a new PolynomialTerm object using the specified coefficient and the specified
	 * integer powers of x, y, and z.
	 */
	public PolynomialTerm(double coefficient, int x, int y, int z) {
		this.setCoefficient(coefficient);
		
		this.setExpOfX(x);
		this.setExpOfY(y);
		this.setExpOfZ(z);
	}
	
	/** Sets the coefficient of this PolynomialTerm object to the specified double value. */
	public void setCoefficient(double coefficient) {
		this.coefficient = coefficient;
	}
	
	/** Sets the exponent on X of this PolynomialTerm object to the specified integer value. */
	public void setExpOfX(int x) { this.expOfX = x; }
	
	/** Sets the exponent on Y of this PolynomialTerm object to the specified integer value. */
	public void setExpOfY(int y) { this.expOfY = y; }
	
	/** Sets the exponent on Z of this PolynomialTerm object to the specified integer value. */
	public void setExpOfZ(int z) { this.expOfZ = z; }
	
	/** Returns the coefficient of this PolynomialTerm object as a double value. */
	public double getCoefficient() { return this.coefficient; }
	
	/** Returns the exponent on X of this PolynomialTerm object as an integer value. */
	public int getExpOfX() { return this.expOfX; }
	
	/** Returns the exponent on Y of this PolynomialTerm object as an integer value. */
	public int getExpOfY() { return this.expOfY; }
	
	/** Returns the exponent on Z of this PolynomialTerm object as an integer value. */
	public int getExpOfZ() { return this.expOfZ; }
	
	/**
	 * Returns the partial derivative of the polynomial term represented by this
	 * PolynomialTerm object with respect to X as a PolynomialTerm object.
	 */
	public PolynomialTerm getDx() {
		if (this.dx == null)
			this.calculateDx();
		
		return this.dx;
	}
	
	/**
	 * Returns the partial derivative of the polynomial term represented by this
	 * {@link PolynomialTerm} with respect to Y as a {@link PolynomialTerm}.
	 */
	public PolynomialTerm getDy() {
		if (this.dy == null)
			this.calculateDy();
		
		return this.dy;
	}
	
	/**
	 * Returns the partial derivative of the polynomial term represented by this
	 * {@link PolynomialTerm} with respect to Z as a {@link PolynomialTerm}.
	 */
	public PolynomialTerm getDz() {
		if (this.dz == null)
			this.calculateDz();
		
		return this.dz;
	}
	
	/**
	 * Evaluates the polynomial term represented by this {@link PolynomialTerm} for
	 * the specified x, y, and z values and returns the result as a double value.
	 */
	public double evaluate(double x, double y, double z) {
		double value = this.coefficient * this.expand(x, this.expOfX) * this.expand(y, this.expOfY) * this.expand(z, this.expOfZ);
		
		return value;
	}
	
	/**
	 * Calculates the partial derivative of the polynomial term represented by this
	 * PolynomialTerm object with respect to X and stores it for later use.
	 */
	public void calculateDx() {
		this.dx = new PolynomialTerm(this.coefficient * this.expOfX, this.expOfX - 1, this.expOfY, this.expOfZ);
	}
	
	/**
	 * Calculates the partial derivative of the polynomial term represented by this
	 * PolynomialTerm object with respect to Y and stores it for later use.
	 */
	public void calculateDy() {
		this.dy = new PolynomialTerm(this.coefficient * this.expOfY, this.expOfX, this.expOfY - 1, this.expOfZ);
	}
	
	/**
	 * Calculates the partial derivative of the polynomial term represented by this
	 * PolynomialTerm object with respect to Z and stores it for later use.
	 */
	public void calculateDz() {
		this.dz = new PolynomialTerm(this.coefficient * this.expOfZ, this.expOfX, this.expOfY, this.expOfZ - 1);
	}
	
	/**
	 * Calculates the product of this PolynomialTerm object with the specified
	 * PolynomialTerm object and returns the result as a PolynomialTerm object.
	 */
	public PolynomialTerm multiply(PolynomialTerm term) {
		PolynomialTerm product = new PolynomialTerm(this.getCoefficient() * term.getCoefficient(),
								this.getExpOfX() + term.getExpOfX(),
								this.getExpOfY() + term.getExpOfY(),
								this.getExpOfZ() + term.getExpOfZ());
		
		return product;
	}
	
	/**
	 * Returns true if the exponents of x, y, and z of this PolynomialTerm object
	 * are the same as the specified PolynomialTerm object.
	 */
	public boolean isLikeTerm(PolynomialTerm term) {
		if (this.getExpOfX() == term.getExpOfX() &&
			this.getExpOfY() == term.getExpOfY() &&
			this.getExpOfZ() == term.getExpOfZ()) {
			return true;
		} else {
			return false;
		}
	}
	
	/**
	 * Raises the specified value to the specified integer power and returns the
	 * result as a double value.
	 */
	public double expand(double value, int power) {
		double output = 1;
		
		if (power == 0) {
			output = 1;
		} else if (power > 0) {
			for(int i = 0; i < power; i++) {
				output = output * value;
			}
		} else if (power < 0) {
			output = 1.0 / this.expand(value, -1 * power);
		}
		
		return output;
	}
	
	public String toString() {
		String value = String.valueOf(this.coefficient);
		
		if (this.expOfX != 0.0) value = value + " * X ^ " + this.expOfX;
		if (this.expOfY != 0.0) value = value + " * Y ^ " + this.expOfY;
		if (this.expOfZ != 0.0) value = value + " * Z ^ " + this.expOfZ;
		
		return value;
	}
}
