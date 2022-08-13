/*
 * Copyright 2022 Michael Murray
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

import org.almostrealism.collect.PackedCollection;

public class DefaultRandomChromosomeFactory implements ChromosomeFactory<PackedCollection<?>> {
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
	
	@Override
	public DefaultRandomChromosomeFactory setChromosomeSize(int genes, int factors) {
		this.genes = genes;
		this.factors = factors;
		return this;
	}
	
	@Override
	public Chromosome<PackedCollection<?>> generateChromosome(double arg) {
		ArrayListChromosome<PackedCollection<?>> c = new ArrayListChromosome<>();
		
		for (int i = 0; i < genes; i++) {
			ArrayListGene<PackedCollection<?>> g = new ArrayListGene<>();
			
			for (int j = 0; j < factors; j++) {
				g.add(new ScaleFactor(min + StrictMath.random() * arg * (max - min)));
			}
			
			c.add(g);
		}
		
		return c;
	}
}
