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

package org.almostrealism.algebra.computations;

import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemWrapper;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public class AcceleratedStaticComputation<T extends MemWrapper> extends AcceleratedStaticComputationAdapter<T> {
	public AcceleratedStaticComputation(T value, Supplier<Evaluable<? extends T>> output) {
		super(value, output);
	}

	@Override
	public KernelizedEvaluable<T> get() { return compileProducer(this); }
}
