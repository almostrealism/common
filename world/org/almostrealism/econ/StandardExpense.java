package org.almostrealism.econ;

public class StandardExpense implements Expense {
	private Unit u;
	private Currency c;

	public StandardExpense() { }

	public StandardExpense(Unit u, Currency c) {
		this.u = u;
		this.c = c;
	}

	public void setUnit(Unit u) { this.u = u; }

	@Override
	public Unit getUnit() { return u; }

	public void setCost(Currency c) { this.c = c; }

	@Override
	public Currency getCost() {
		return c;
	}
}