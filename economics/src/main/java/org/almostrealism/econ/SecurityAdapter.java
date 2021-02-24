package org.almostrealism.econ;

public abstract class SecurityAdapter implements Security {
	private ExpenseData ed;

	public SecurityAdapter(ExpenseData d) {
		this.ed = d;
	}

	public Currency buy(Time t, double amount) {
		return ed.get(t).getCost().multiply(amount);
	}

	public Currency sell(Time t, double amount) {
		return ed.get(t).getCost().multiply(amount);
	}
}
