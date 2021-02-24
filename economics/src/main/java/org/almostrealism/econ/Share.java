package org.almostrealism.econ;

public class Share extends FloatingPointUnit {
	public Share() { this(1); }

	protected Share(double amount) { super(amount); }

	public double getAmount() { return asDouble(); }
}
