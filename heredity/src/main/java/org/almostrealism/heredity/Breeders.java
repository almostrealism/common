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

import org.almostrealism.algebra.Scalar;

import java.util.function.BiFunction;
import java.util.function.DoubleFunction;

public class Breeders {
	private Breeders() { }

	public static <T> ChromosomeBreeder<T> randomChoiceBreeder() {
		return byCombiningGenes((g1, g2) -> StrictMath.random() < 0.5 ? g1 : g2);
	}

	public static ChromosomeBreeder<Scalar> averageBreeder() {
		return byCombiningFactors((f1, f2) ->
				new ScaleFactor((((ScaleFactor) f1).getScale().getValue() + ((ScaleFactor) f1).getScale().getValue()) / 2.0));
	}

	public static <T> ChromosomeBreeder<T> perturbationBreeder(double magnitude, DoubleFunction<Factor<T>> factory) {
		return byCombiningFactors((f1, f2) -> {
			double s1, s2;

//			if (Math.random() < 0.5) {
//				s1 = ((ScaleFactor) f1).getScale();
//				s2 = ((ScaleFactor) f2).getScale();
//			} else {
//				s1 = ((ScaleFactor) f2).getScale();
//				s2 = ((ScaleFactor) f1).getScale();
//			}

			s1 = ((ScaleFactor) f1).getScale().getValue();
			s2 = ((ScaleFactor) f2).getScale().getValue();

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

			return factory.apply(s1 + m);
		});
	}

	public static <T> ChromosomeBreeder<T> of(ChromosomeBreeder<T>... factorBreeders) {
		return (c1, c2) -> {
			ArrayListChromosome<T> chrom = new ArrayListChromosome<>();

			for (int i = 0; i < c1.length() && i < c2.length(); i++) {
				ArrayListGene<T> gene = new ArrayListGene<>();

				for (int j = 0; j < factorBreeders.length; j++) {
					gene.add(factorBreeders[j].combine(c1, c2).apply(i).apply(j));
				}

				chrom.add(gene);
			}

			return chrom;
		};
	}

	public static <T> ChromosomeBreeder<T> byCombiningGenes(BiFunction<Gene<T>, Gene<T>, Gene<T>> combine) {
		return (c1, c2) -> {
			ArrayListChromosome<T> chrom = new ArrayListChromosome<>();

			for (int i = 0; i < c1.length() && i < c2.length(); i++) {
				Gene<T> g1 = c1.valueAt(i);
				Gene<T> g2 = c2.valueAt(i);
				chrom.add(combine.apply(g1, g2));
			}

			return chrom;
		};
	}

	public static <T> ChromosomeBreeder<T> byCombiningFactors(BiFunction<Factor<T>, Factor<T>, Factor<T>> combine) {
		return byCombiningGenes((g1, g2) -> {
			ArrayListGene<T> gene = new ArrayListGene<>();

			for (int i = 0; i < g1.length() && i < g2.length(); i++) {
				gene.add(combine.apply(g1.valueAt(i), g2.valueAt(i)));
			}

			return gene;
		});
	}
}
