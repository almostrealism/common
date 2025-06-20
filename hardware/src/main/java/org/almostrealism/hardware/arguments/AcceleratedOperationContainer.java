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

import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ProducerSubstitution;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ProducerCache;
import org.almostrealism.hardware.instructions.ExecutionKey;
import org.almostrealism.hardware.instructions.InstructionSetManager;

import java.util.List;
import java.util.function.Supplier;

@Deprecated
public class AcceleratedOperationContainer<T extends MemoryData>
		implements Countable, Evaluable<T>, ProcessArgumentEvaluator {
	private AcceleratedOperation<T> operation;
	private ThreadLocal<List<ProducerSubstitution<?>>> substitutions;

	public AcceleratedOperationContainer(AcceleratedOperation<T> operation) {
		this.operation = operation;
		this.substitutions = new ThreadLocal<>();
	}

	public void setSubstitutions(List<ProducerSubstitution<?>> substitutions) {
		this.substitutions.set(substitutions);
		this.operation.getDetailsFactory().reset();
	}

	public void clearSubstitutions() {
		this.substitutions.remove();
	}

	public <K extends ExecutionKey> InstructionSetManager<K> getInstructionSetManager() {
		return operation.getInstructionSetManager();
	}

	@Override
	public T evaluate(Object... args) {
		if (operation instanceof Evaluable<?>) {
			return ((Evaluable<T>) operation).evaluate(args);
		}

		throw new UnsupportedOperationException();
	}

	@Override
	public long getCountLong() { return operation.getCountLong(); }

	@Override
	public <V> Evaluable<? extends Multiple<V>> getEvaluable(ArrayVariable<V> argument) {
		return getEvaluable(argument.getProducer());
	}

	public <V> Evaluable<? extends V> getEvaluable(Supplier<Evaluable<? extends V>> producer) {
		List<ProducerSubstitution<?>> subs = substitutions.get();

		if (subs != null) {
			for (ProducerSubstitution<?> s : subs) {
				if (s.match(producer)) {
					return (Evaluable) ProducerCache.getEvaluableForSupplier(s.getReplacement());
				}
			}
		}

		return ProducerCache.getEvaluableForSupplier(producer);
	}
}
