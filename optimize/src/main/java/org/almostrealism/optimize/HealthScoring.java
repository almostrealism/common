package org.almostrealism.optimize;

public class HealthScoring {
	private int popSize;
	private double highestHealth, totalHealth;

	public HealthScoring(int popSize) {
		this.popSize = popSize;
	}

	public synchronized void pushScore(HealthScore health) {
		if (health.getScore() > highestHealth) highestHealth = health.getScore();
		totalHealth += health.getScore();
	}

	public synchronized double getAverageScore() {
		return totalHealth / popSize;
	}

	public synchronized double getMaxScore() {
		return highestHealth;
	}
}
