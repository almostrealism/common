package org.almostrealism.econ;

public interface Security extends MonetaryValue {
	Expense buy(Time t, double amount);
	Expense sell(Time t, double amount);
}
