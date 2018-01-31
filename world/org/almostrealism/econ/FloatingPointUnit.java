package org.almostrealism.econ;

public class FloatingPointUnit implements Unit {
	private double value;

	// TODO  Support FP digit cap (2 digits for most dollar amounts, 3 4 or 5 digits for stocks, etc)
	public FloatingPointUnit(double d) {
		this.value = d;
	}

	public double asDouble() { return value; }

	public int asInteger() { return (int) value; }

	public long asLong() { return (long) value; }
}
