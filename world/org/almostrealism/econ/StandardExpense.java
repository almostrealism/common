package org.almostrealism.econ;

public class StandardExpense implements Expense {
	private Unit u;
	private Currency c;

	public StandardExpense(Unit u, Currency c) {
		this.u = u;
		this.c = c;
	}

	@Override
	public Unit getUnit() {
		return u;
	}

	@Override
	public Currency getCost() {
		return c;
	}
}
