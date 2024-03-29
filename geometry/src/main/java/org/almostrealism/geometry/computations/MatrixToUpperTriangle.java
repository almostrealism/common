/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.geometry.computations;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.hardware.AcceleratedEvaluable;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public class MatrixToUpperTriangle extends AcceleratedEvaluable<MemoryData, TransformMatrix> {
	public MatrixToUpperTriangle(Supplier<Evaluable<? extends MemoryData>> m) {
		super("matrixToUpperTriangle", TransformMatrix.blank(),
				(Supplier) Scalar.blank(), m);
		setKernelDestination(TransformMatrix::bank);
		setPostprocessor(TransformMatrix.postprocessor());
	}
}
