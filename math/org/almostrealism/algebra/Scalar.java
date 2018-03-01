package org.almostrealism.algebra;

public class Scalar extends Pair implements Comparable<Scalar> {
	public static final double EPSILON = 1.19209290e-07;
	public static final double TWO_PI = 6.283185307179586232;
	public static final double PI = TWO_PI * 0.5;

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

	public static Scalar sel(double a, double b, double c) {
		return new Scalar((a >= 0 ? b : c));
	}

	public static boolean fuzzyZero(double x) {
		return Math.abs(x) < EPSILON;
	}

	public static Scalar atan2(double y, double x) {
		double coeff_1 = PI / 4.0;
		double coeff_2 = 3.0 * coeff_1;
		double abs_y = Math.abs(y);
		double angle;

		if (x >= 0.0) {
			double r = (x - abs_y) / (x + abs_y);
			angle = coeff_1 - coeff_1 * r;
		} else {
			double r = (x + abs_y) / (abs_y - x);
			angle = coeff_2 - coeff_1 * r;
		}

		return new Scalar(((y < 0.0f) ? -angle : angle));
	}
}
