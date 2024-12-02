/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.hardware.AcceleratedEvaluable;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The reason this is deprecated is mostly because it should be an operation
 * rather than an evaluable, and because confusion like that has led to a
 * shortcut of invoking prepareScope in the constructor which is a bad idea.
 */
@Deprecated
public class MatrixDeterminant extends AcceleratedEvaluable<TransformMatrix, Scalar> {
	public MatrixDeterminant(Supplier<Evaluable<? extends TransformMatrix>> m) {
		super("matrixDeterminant", Scalar.blank(), m);
		setKernelDestination(Scalar::scalarBank);
		setPostprocessor((BiFunction) Scalar.postprocessor());
	}
}
