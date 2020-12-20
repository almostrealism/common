/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.color.computations;

import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBBank;
import org.almostrealism.hardware.AcceleratedComputationProducer;
import org.almostrealism.hardware.MemoryBank;
import io.almostrealism.relation.Computation;

public class DefaultRGBEvaluable extends AcceleratedComputationProducer<RGB> implements RGBEvaluable {

	public DefaultRGBEvaluable(Computation<RGB> c) {
		super(c);
	}

	@Override
	public MemoryBank<RGB> createKernelDestination(int size) {
		return new RGBBank(size);
	}
}
