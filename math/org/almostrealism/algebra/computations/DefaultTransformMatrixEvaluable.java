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

package org.almostrealism.algebra.computations;

import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.TransformMatrixBank;
import org.almostrealism.algebra.TransformMatrixEvaluable;
import org.almostrealism.hardware.AcceleratedComputationProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.relation.Computation;

public class DefaultTransformMatrixEvaluable extends AcceleratedComputationProducer<TransformMatrix> implements TransformMatrixEvaluable {

	public DefaultTransformMatrixEvaluable(Computation<TransformMatrix> c) {
		super(c);
	}

	@Override
	public MemoryBank<TransformMatrix> createKernelDestination(int size) {
		return new TransformMatrixBank(size);
	}
}
