/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.graph;

import java.util.Random;

import org.almostrealism.graph.Cell;

public class ProbabilityDensityCellFactory<T> implements CellFactory<T> {
	private Random rand = new Random();
	
	private CellFactory<T> choices[];
	private double bias[];
	private double jitter;
	
	public ProbabilityDensityCellFactory(CellFactory<T> choices[]) {
		this(choices, null);
	}
	
	public ProbabilityDensityCellFactory(CellFactory<T> choices[], double biasFactors[]) {
		this.choices = choices;
		this.bias = biasFactors;
		
		if (this.bias == null) {
			this.bias = new double[choices.length];
			for (int i = 0; i < bias.length; i++) bias[i] = 1.0;
		}
	}
	
	public void setJitter(double jitterIntensity) { this.jitter = jitterIntensity; }
	
	public Cell<T> generateCell(double arg) {
		// Pick a point in N-Space, starting
		// with the bias values initially
		double random[] = generateRandoms(getBias(arg), 1.0);
		
		// Jitter the point
		random = generateRandoms(random, jitter);
		
		// Collection of distance calculations
		double distances[] = new double[choices.length];
		
		// Calculate the distance of the random point
		// to each of the linearly independent vectors
		for (int i = 0; i < distances.length; i++) {
			distances[i] = distanceToAxis(random, i);
		}
		
		// Smallest distance values
		int smallestIndex = 0;
		double smallestValue = distances[0];
		
		// Determine the smallest distance
		for (int i = 1; i < distances.length; i++) {
			if (distances[i] < smallestValue) {
				smallestIndex = i;
				smallestValue = distances[i];
			}
		}
		
		// Return a new cell from the factory which corresponds to
		// independent vector which has the lowest distance to the
		// randomly selected point. This distance is passed as an
		// argument to the generateCell method, so that density
		// cell factories can be daisy chained if desired.
		return choices[smallestIndex].generateCell(smallestValue);
	}
	
	private double[] getBias(double centerOfBell) {
		double b[] = new double[choices.length];
		double delta = 1.0 / b.length;
		
		for (int i = 0; i < b.length; i++) {
			b[i] = bias[i]; // Start with the initial bias
			
			// Add the area under the bell curve that is
			// centered at the argument value
			b[i] += bellCurveArea(centerOfBell, i * delta, (i + 1) * delta);
		}
		
		return b;
	}
	
	private double bellCurveArea(double center, double integralStart, double integralEnd) {
		return 0; // TODO
	}
	
	private double distanceToAxis(double random[], int axis) {
		double total = 0.0;
		
		for (int i = 0; i < random.length; i++) {
			double v;
			
			if (i == axis) {
				v = 1.0 - random[i];
			} else {
				v = random[i] * random[i];
			}
			
			total += v * v;
		}
		
		return Math.sqrt(total);
	}
	
	private double[] generateRandoms() {
		return generateRandoms(new double[choices.length], 1.0);
	}
	
	private double[] generateRandoms(double scale) {
		return generateRandoms(new double[choices.length], scale);
	}
	
	private double[] generateRandoms(double initial[]) {
		return generateRandoms(initial, 1.0);
	}
	
	private double[] generateRandoms(double initial[], double scale) {
		double r[] = new double[choices.length];
		for (int i = 0; i < r.length; i++) r[i] = initial[i] + (scale * rand.nextDouble());
		return r;
	}
}
