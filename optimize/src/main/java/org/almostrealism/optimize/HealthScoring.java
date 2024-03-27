/*
 * Copyright 2024 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

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
