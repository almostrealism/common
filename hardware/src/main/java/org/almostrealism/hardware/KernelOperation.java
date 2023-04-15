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

import java.util.function.Supplier;

public class KernelOperation<T extends MemoryData> implements Supplier<Runnable> {
	private KernelizedProducer<T> producer;
	private MemoryBank destination;
	private MemoryData arguments[];

	public KernelOperation(KernelizedProducer<T> producer, MemoryBank destination, MemoryData... arguments) {
		this.producer = producer;
		this.destination = destination;
		this.arguments = arguments;
	}

	@Override
	public Runnable get() {
		KernelizedEvaluable<T> ev = producer.get();
		return () -> ev.kernelEvaluate(destination, arguments);
	}
}
