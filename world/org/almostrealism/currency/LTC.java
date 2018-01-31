package org.almostrealism.currency;

import org.almostrealism.econ.Currency;
import org.almostrealism.econ.Share;

public class LTC extends Share implements Currency {
	public LTC(double amount) { super(amount); }

	public LTC multiply(double amount) { return new LTC(asDouble() * amount); }
}
