/*
 * Copyright 2023 Michael Murray
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

@FunctionalInterface
public interface Factor<T> extends Function<T, T> {
	Producer<T> getResultant(Producer<T> value);

	default String signature() { throw new UnsupportedOperationException(); }

	default Factor<T> andThen(Factor<T> next) {
		throw new UnsupportedOperationException();
	}
}
