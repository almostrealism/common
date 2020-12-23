package org.almostrealism.currency;

import org.almostrealism.econ.Currency;
import org.almostrealism.econ.FloatingPointUnit;

public class USD extends FloatingPointUnit implements Currency<BTC> {
	public USD() { super(1.0); }

	public USD(double amount) { super(amount); }

	public void setAmount(double amount) { super.setValue(amount); }

	public BTC multiply(double amount) { return new BTC(asDouble() * amount); }
}
