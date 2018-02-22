package org.almostrealism.algebra;

public class Pair {
	double x, y;

	public Pair() { }

	public Pair(double x, double y) {
		this.x = x;
		this.y = y;
	}
	
	public Pair setX(double x) { this.x = x; return this; }
	public Pair setY(double y) { this.y = y; return this; }
	public Pair setA(double a) { this.x = a; return this; }
	public Pair setB(double b) { this.y = b; return this; }
	public Pair setLeft(double l) { this.x = l; return this; }
	public Pair setRight(double r) { this.y = r; return this; }
	public Pair setTheta(double t) { this.x = t; return this; }
	public Pair setPhi(double p) { this.y = p; return this; }
	public double getX() { return x; }
	public double getY() { return y; }
	public double getA() { return x; }
	public double getB() { return y; }
	public double getLeft() { return x; }
	public double getRight() { return y; }
	public double getTheta() { return x; }
	public double getPhi() { return y; }
	public double x() { return x; }
	public double y() { return y; }
	public double a() { return x; }
	public double b() { return y; }
	public double left() { return x; }
	public double right() { return y; }
	public double theta() { return x; }
	public double phi() { return y; }
	public double _1() { return x; }
	public double _2() { return y; }
}
