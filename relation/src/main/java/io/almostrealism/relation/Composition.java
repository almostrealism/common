/*
 * Copyright 2024 Michael Murray
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

/**
 * A {@link Composition} composes the computational system represented by two independent
 * {@link Producer}s, into a new system, while preserving the type of the ultimate result.
 *
 * @param <T>  The type of the ultimate result of computation.
 */
@FunctionalInterface
public interface Composition<T> {
	Producer<T> compose(Producer<T> a, Producer<T> b);

	default Composition<T> andThen(Factor<T> next) {
		return (a, b) -> next.getResultant(Composition.this.compose(a, b));
	}
}
