package org.almostrealism.econ;

public interface Expense {
	Unit getUnit();
	Currency getCost();
	Expense multiply(double amount);
}
