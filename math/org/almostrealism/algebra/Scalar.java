package org.almostrealism.algebra;

public class Scalar extends Pair implements Comparable<Scalar> {
	public Scalar() { setCertainty(1.0); }
	public Scalar(double v) { setValue(v); setCertainty(1.0); }

	public Scalar setValue(double v) { setLeft(v); return this; }
	public Scalar setCertainty(double c) { setRight(c); return this; }
	public double getValue() { return left(); }
	public double getCertainty() { return right(); }

	@Override
	public int compareTo(Scalar s) {
		double m = 2 * Math.max(Math.abs(getValue()), Math.abs(s.getValue()));
		return (int) ((this.getValue() - s.getValue() / m) * Integer.MAX_VALUE);
	}
}
