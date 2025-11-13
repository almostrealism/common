/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.hardware.mem;

import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.util.DescribableParent;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.io.Describable;

import java.util.function.BiFunction;
import java.util.function.IntFunction;

public class MemoryDataDestinationProducer<T extends MemoryData> extends DynamicProducerForMemoryData<T>
												implements Delegated<Countable>, DescribableParent<Process<?, ?>> {

	private final Countable process;

	public MemoryDataDestinationProducer(Countable process) {
		this(process, (IntFunction<MemoryBank<T>>) null);
	}

	public MemoryDataDestinationProducer(Countable process, IntFunction<MemoryBank<T>> destination) {
		super(destination);
		this.process = process;
	}

	public MemoryDataDestinationProducer(Countable process, BiFunction<MemoryBank<T>, Integer, MemoryBank<T>> destination) {
		super((IntFunction<MemoryBank<T>>)  i -> destination.apply(null, i));
		this.process = process;
	}

	@Override
	public Countable getDelegate() { return process; }

	@Override
	public long getCountLong() {
		if (process != null) {
			return process.getCountLong();
		}

		return super.getCountLong();
	}

	@Override
	public Evaluable<T> get() {
		return new MemoryDataDestination<>(size -> getDestinationFactory().apply(size));
	}

	@Override
	public void destroy() {
		super.destroy();
	}

	@Override
	public String describe() {
		return getDelegate() instanceof Describable ?
				((Describable) getDelegate()).describe() :
				getDelegate().getClass().getSimpleName();
	}

	@Override
	public String description() {
		return "";
	}
}
