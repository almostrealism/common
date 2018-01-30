package org.almostrealism.econ;

public abstract class SecurityAdapter implements Security {
	private ExpenseData ed;

	public SecurityAdapter(ExpenseData d) {
		this.ed = d;
	}

	public Expense buy(Time t, double amount) {
		return ed.get(t).multiply(amount);
	}

	public Expense sell(Time t, double amount) {
		return ed.get(t).multiply(amount);
	}
}
