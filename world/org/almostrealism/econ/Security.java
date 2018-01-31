package org.almostrealism.econ;

public interface Security extends MonetaryValue {
	Currency buy(Time t, double amount);
	Currency sell(Time t, double amount);
}
