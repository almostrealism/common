/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.heredity;

/**
 * Utility class providing static methods for breeding operations in genetic algorithms.
 *
 * <p>This class contains helper methods commonly used during crossover and mutation
 * operations. The methods are designed to support bounded value perturbation and
 * other common genetic algorithm operations.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Perturb a value within bounds
 * double original = 0.5;
 * double lowerBound = 0.0;
 * double upperBound = 1.0;
 * double perturbAmount = 0.3;
 *
 * double result = Breeders.perturbation(lowerBound, upperBound, perturbAmount);
 * // Result will be bounded to stay within [0.0, 1.0]
 * }</pre>
 *
 * @see ChromosomeBreeder
 * @see GenomeBreeder
 */
public class Breeders {
	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private Breeders() { }

	/**
	 * Calculates a bounded perturbation value that moves from {@code s1} toward {@code s2}
	 * by the specified magnitude, ensuring the result stays within bounds.
	 *
	 * <p>This method is useful for mutation operations where you want to perturb a value
	 * in a specific direction but ensure it doesn't exceed certain limits. The perturbation
	 * is automatically clamped if the magnitude would cause the value to overshoot {@code s2}.
	 *
	 * <h3>Behavior</h3>
	 * <ul>
	 *   <li>If {@code s2 > s1}: adds magnitude (clamped to not exceed s2)</li>
	 *   <li>If {@code s1 > s2}: subtracts magnitude (clamped to not go below s2)</li>
	 *   <li>If {@code s1 == s2}: returns s1 (no perturbation possible)</li>
	 * </ul>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * // Moving from 0.0 toward 1.0 by 0.3
	 * double result1 = Breeders.perturbation(0.0, 1.0, 0.3);  // Returns 0.3
	 *
	 * // Moving from 0.0 toward 1.0 by 2.0 (clamped)
	 * double result2 = Breeders.perturbation(0.0, 1.0, 2.0);  // Returns 1.0
	 *
	 * // Moving from 1.0 toward 0.0 by 0.3
	 * double result3 = Breeders.perturbation(1.0, 0.0, 0.3);  // Returns 0.7
	 * }</pre>
	 *
	 * @param s1 the starting value and first bound
	 * @param s2 the target direction and second bound
	 * @param magnitude the amount to perturb (absolute value is used)
	 * @return the perturbed value, bounded between s1 and s2
	 */
	public static double perturbation(double s1, double s2, double magnitude) {
		double m = magnitude;
		if (s2 > s1) {
			if (Math.abs(m) > s2 - s1) {
				m = m > 0 ? s2 - s1 : s1 - s2;
			}
		} else if (s1 > s2) {
			if (Math.abs(m) > s1 - s2) {
				m = m > 0 ? s1 - s2 : s2 - s1;
			}

			m = -m;
		} else {
			m = 0;
		}

		return s1 + m;
	}
}
