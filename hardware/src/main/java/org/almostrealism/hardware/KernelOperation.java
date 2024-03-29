/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;

import java.util.function.Supplier;

@Deprecated
public class KernelOperation<T extends MemoryData> implements Supplier<Runnable> {
	private Producer<T> producer;
	private Evaluable<T> evaluable;
	private MemoryBank destination;
	private MemoryData arguments[];

	public KernelOperation(Producer<T> producer, MemoryBank destination, MemoryData... arguments) {
		this.producer = producer;
		this.destination = destination;
		this.arguments = arguments;
	}

	public KernelOperation(Evaluable<T> evaluable, MemoryBank destination, MemoryData... arguments) {
		this.evaluable = evaluable;
		this.destination = destination;
		this.arguments = arguments;
	}

	@Override
	public Runnable get() {
		Evaluable<T> ev = evaluable == null ? producer.get() : evaluable;
		return () -> ev.into(destination).evaluate(arguments);
	}
}
