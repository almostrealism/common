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

package io.almostrealism.code;

import io.almostrealism.relation.Evaluable;

import java.util.function.Function;
import java.util.function.Supplier;

public interface ArgumentProvider {
	<T> ArrayVariable<T> getArgument(NameProvider p, Supplier<Evaluable<? extends T>> input, ArrayVariable<T> delegate, int delegateOffset);

	default <T> Function<Supplier<Evaluable<? extends T>>, ArrayVariable<T>> argumentForInput(NameProvider p) {
		return input -> input == null ? null : getArgument(p, input, null, -1);
	}
}
