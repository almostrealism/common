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

package org.almostrealism.hardware.arguments;

import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerSubstitution;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryData;

import java.util.ArrayList;
import java.util.List;

public class AcceleratedSubstitutionEvaluable<T extends MemoryData> implements KernelizedEvaluable<T> {
	private AcceleratedOperationContainer<T> container;
	protected List<ProducerSubstitution<?>> substitutions;

	public AcceleratedSubstitutionEvaluable(AcceleratedOperationContainer<T> container) {
		this.container = container;
		this.substitutions = new ArrayList<>();
	}

	public <V> void addSubstitution(Producer<V> original, Producer<V> replacement) {
		substitutions.add(new ProducerSubstitution<>(original, replacement));
	}

	public <V> void addSubstitution(ProducerSubstitution<V> substitution) {
		substitutions.add(substitution);
	}

	@Override
	public T evaluate(Object... args) {
		try {
			container.setSubstitutions(substitutions);
			return container.evaluate(args);
		} finally {
			container.clearSubstitutions();
		}
	}
}
