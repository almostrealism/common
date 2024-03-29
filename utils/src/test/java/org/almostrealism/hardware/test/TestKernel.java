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

package org.almostrealism.hardware.test;

import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.hardware.AcceleratedEvaluable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.cl.CLComputeContext;

import java.util.function.Supplier;

public class TestKernel extends AcceleratedEvaluable<Ray, Ray> {
	@SafeVarargs
	public TestKernel(CLComputeContext context, Supplier<Evaluable<? extends Ray>> blank, Supplier<Evaluable<? extends Ray>>... inputArgs) {
		super(context, "testKernel", true, blank, inputArgs);
		setKernelDestination(Ray::bank);
		setPostprocessor(Ray.postprocessor());
	}
}
