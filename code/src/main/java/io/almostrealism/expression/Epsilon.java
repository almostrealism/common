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

package io.almostrealism.expression;

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.OptionalInt;

public class Epsilon extends StaticReference<Double> {

	public Epsilon() {
		super(Double.class, null);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.getPrecision().stringForDouble(lang.getPrecision().epsilon());
	}

	@Override
	public OptionalInt upperBound(KernelStructureContext context) {
		return OptionalInt.of(1);
	}

	@Override
	public Number evaluate(Number... children) {
		return 0.0;
	}
}
