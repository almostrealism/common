/*
 * Copyright 2017 Michael Murray
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

public class DefaultRandomChromosomeFactory implements ChromosomeFactory<Long> {
	private double min, max;
	private int genes, factors;
	
	public DefaultRandomChromosomeFactory() { this (1.0); }
	
	public DefaultRandomChromosomeFactory(double largestScale) {
		this.max = largestScale;
	}
	
	public DefaultRandomChromosomeFactory(double smallestScale, double largestScale) {
		this.min = smallestScale;
		this.max = largestScale;
	}
	
	public DefaultRandomChromosomeFactory setChromosomeSize(int genes, int factors) {
		this.genes = genes;
		this.factors = factors;
		return this;
	}
	
	public Chromosome<Long> generateChromosome(double arg) {
		ArrayListChromosome<Long> c = new ArrayListChromosome<Long>();
		
		for (int i = 0; i < genes; i++) {
			ArrayListGene<Long> g = new ArrayListGene<Long>();
			
			for (int j = 0; j < factors; j++) {
				g.add(new NewLongScaleFactor(min + StrictMath.random() * arg * (max - min)));
			}
			
			c.add(g);
		}
		
		return c;
	}
}
