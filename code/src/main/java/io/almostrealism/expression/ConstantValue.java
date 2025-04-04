/*
 * Copyright 2024 Michael Murray
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

import java.util.Objects;

public class ConstantValue<T> extends Constant<T> {
	private T value;

	public ConstantValue(Class<T> type, T value) {
		super(type);
		this.value = value;
		init();
	}

	@Override
	public boolean compare(Expression e) {
		return e instanceof ConstantValue && Objects.equals(((ConstantValue) e).value, value);
	}
}
