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

import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.lang.LanguageOperations;

import java.util.Optional;

public class BooleanConstant extends Constant<Boolean> {
	private boolean value;

	public BooleanConstant(Boolean value) {
		super(Boolean.class);
		this.value = value;
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return String.valueOf(value);
	}

	@Override
	public Optional<Boolean> booleanValue() {
		return Optional.of(value);
	}

	@Override
	public IndexSequence sequence(Index index, int len) {
		return IndexSequence.of(value ? 1 : 0, len);
	}

	@Override
	public Number evaluate(Number... children) {
		return value ? 1 : 0;
	}
}
