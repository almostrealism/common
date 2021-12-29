/*
 * Copyright 2020 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.relation;

import io.almostrealism.uml.Function;

/**
 * The {@link Evaluable} interface is implemented by classes that represent a
 * repeatedly evaluated function.
 *
 * @author  Michael Murray
 */
@Function
public interface Evaluable<T> {
	default T evaluate() {
		return evaluate(new Object[0]);
	}

	T evaluate(Object... args);
}
