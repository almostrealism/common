/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.color.computations;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.geometry.computations.RankedChoiceEvaluableForMemoryData;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.NullProcessor;

public class RankedChoiceEvaluableForRGB extends RankedChoiceEvaluableForMemoryData<RGB> implements NullProcessor<RGB>, RGBFeatures {
	public RankedChoiceEvaluableForRGB(double e) {
		super(e);
	}

	public RankedChoiceEvaluableForRGB(double e, boolean tolerateNull) {
		super(e, tolerateNull);
	}

	public Evaluable<RGB> getAccelerated() {
		return getAccelerated(3, RGB::new, RGB::bank);
	}

	@Override
	public RGB replaceNull(Object[] args) {
		if (tolerateNull) {
			return black().get().evaluate();
		} else {
			throw new NullPointerException();
		}
	}

	@Override
	public MemoryBank<RGB> createDestination(int size) { return RGB.bank(size); }
}
