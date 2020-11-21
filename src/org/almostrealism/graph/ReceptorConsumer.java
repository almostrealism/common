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

package org.almostrealism.graph;

import org.almostrealism.util.Producer;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class ReceptorConsumer<T> implements Receptor<T> {
	private Consumer<T> consumer;

	public ReceptorConsumer(Consumer<T> c) {
		this.consumer = c;
	}

	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		return () -> () -> consumer.accept(protein.evaluate());
	}

	protected void setConsumer(Consumer<T> c) { this.consumer = c; }
}
