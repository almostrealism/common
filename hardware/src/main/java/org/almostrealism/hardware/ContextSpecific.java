/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.code.DataContext;

import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ContextSpecific<T> implements ContextListener {
	private Stack<T> val;
	private Supplier<T> supply;
	private Consumer<T> disposal;

	public ContextSpecific(Supplier<T> supply) {
		this(supply, null);
	}

	public ContextSpecific(Supplier<T> supply, Consumer<T> disposal) {
		this.val = new Stack<>();
		this.supply = supply;
		this.disposal = disposal;
	}

	public void init() {
		if (val.isEmpty()) val.push(supply.get());
		Hardware.getLocalHardware().addContextListener(this);
	}

	public T getValue() {
		return val.peek();
	}

	@Override
	public void contextStarted(DataContext ctx) {
		val.push(supply.get());
	}

	@Override
	public void contextDestroyed(DataContext ctx) {
		if (disposal == null) {
			val.pop();
		} else {
			disposal.accept(val.pop());
		}
	}
}
