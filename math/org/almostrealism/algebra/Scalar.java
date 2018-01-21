package org.almostrealism.algebra;

public class Scalar extends Pair {
	public Scalar setValue(double v) { setLeft(v); return this; }
	public Scalar setCertainty(double c) { setRight(c); return this; }
	public double getValue() { return left(); }
	public double getCertainty() { return right(); }
}
