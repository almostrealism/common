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

public class FloatingPointRandomChromosomeFactory implements ChromosomeFactory<Double> {
	private int genes, factors;
	
	public FloatingPointRandomChromosomeFactory setChromosomeSize(int genes, int factors) {
		this.genes = genes;
		this.factors = factors;
		return this;
	}
	
	public Chromosome<Double> generateChromosome(double arg) {
		ArrayListChromosome<Double> c = new ArrayListChromosome<Double>();
		
		for (int i = 0; i < genes; i++) {
			ArrayListGene<Double> g = new ArrayListGene<Double>();
			
			for (int j = 0; j < factors; j++) {
				g.add(new DoubleScaleFactor(StrictMath.random() * arg));
			}
			
			c.add(g);
		}
		
		return c;
	}
}
