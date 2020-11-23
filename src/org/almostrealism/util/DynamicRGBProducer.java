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

package org.almostrealism.util;

import io.almostrealism.code.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBBank;
import org.almostrealism.color.computations.RGBProducer;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.relation.Evaluable;
import org.almostrealism.relation.NameProvider;

import java.util.function.Function;

public class DynamicRGBProducer extends DynamicProducer<RGB> implements RGBProducer {

	public DynamicRGBProducer(Function<Object[], RGB> function) {
		super(function);
	}

	@Override
	public Scope<RGB> getScope(NameProvider provider) {
		throw new RuntimeException("Not implemented");
	}

	@Override
	public Evaluable<RGB> get() {
		Evaluable<RGB> e = super.get();

		return new KernelizedEvaluable<RGB>() {
			@Override
			public MemoryBank<RGB> createKernelDestination(int size) { return new RGBBank(size); }

			@Override
			public RGB evaluate(Object... args) { return e.evaluate(args); }
		};
	}
}
