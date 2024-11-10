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

package io.almostrealism.relation;

import java.util.function.Supplier;

public class ProducerSubstitution<T> {
	private Producer<T> original;
	private Producer<T> replacement;

	public ProducerSubstitution(Producer<T> original, Producer<T> replacement) {
		this.original = original;
		this.replacement = replacement;
	}

	public Producer<T> getOriginal() { return original; }

	public Producer<T> getReplacement() { return replacement; }

	public <V> boolean match(Supplier<Evaluable<? extends V>> producer) {
		return producer == (Supplier) original;
	}
}
