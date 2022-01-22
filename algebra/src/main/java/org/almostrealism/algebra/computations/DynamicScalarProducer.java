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

import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.hardware.DynamicProducerForMemoryData;

import java.util.function.Function;

public class DynamicScalarProducer extends DynamicProducerForMemoryData<Scalar> implements ScalarProducer {

	public DynamicScalarProducer(Function<Object[], Scalar> function) {
		super(function, ScalarBank::new);
	}

	@Override
	public Scope<Scalar> getScope() {
		throw new RuntimeException("Not implemented");
	}
}
