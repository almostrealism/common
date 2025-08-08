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

import io.almostrealism.relation.Factor;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

public class Breeders {
	private Breeders() { }

	private static PackedCollection<?> value(Factor<PackedCollection<?>> factor) {
		if (factor instanceof ScaleFactor) {
			return ((ScaleFactor) factor).getScale();
		} else if (factor instanceof AssignableGenome.AssignableFactor) {
			return ((AssignableGenome.AssignableFactor) factor).getValue();
		} else {
			return factor.getResultant(CollectionFeatures.getInstance().c(1.0)).get().evaluate();
		}
	}

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
