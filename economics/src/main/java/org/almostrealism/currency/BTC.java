package org.almostrealism.currency;

import org.almostrealism.econ.Currency;
import org.almostrealism.econ.Share;

public class BTC extends Share implements Currency<BTC> {
	public BTC() { }

	public BTC(double amount) { super(amount); }

	public void setAmount(double amount) { super.setValue(amount); }

	public BTC multiply(double amount) { return new BTC(asDouble() * amount); }
}
