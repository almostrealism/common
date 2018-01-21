package org.almostrealism.algebra;

public class ComplexNumber extends Pair {
	public double getRealPart() { return left(); }
	public double getImaginaryPart() { return right(); }
	public double r() { return getRealPart(); }
	public double i() { return getImaginaryPart(); }
}
