package org.almostrealism.currency;

import org.almostrealism.econ.Currency;
import org.almostrealism.econ.Share;

public class LTC extends Share implements Currency {
	public LTC() { }

	public LTC(double amount) { super(amount); }

	public void setAmount(double amount) { super.setValue(amount); }

	public LTC multiply(double amount) { return new LTC(asDouble() * amount); }
}
