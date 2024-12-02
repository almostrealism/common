/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.hardware.ctx;

import io.almostrealism.code.DataContext;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.lifecycle.SuppliedValue;
import org.almostrealism.hardware.Hardware;

import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class ContextSpecific<T> implements ContextListener, Destroyable, ConsoleFeatures {
	private Stack<SuppliedValue<T>> val;
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
		if (val.isEmpty()) val.push(createValue(supply));
		Hardware.getLocalHardware().addContextListener(this);
	}

	public T getValue() {
		if (val.isEmpty()) val.push(createValue(supply));

		T v = val.peek().getValue();

		if (val.size() > 3) {
			warn(val.size() + " context layers for " + v.getClass().getSimpleName());
		}

		return v;
	}

	public abstract SuppliedValue<T> createValue(Supplier<T> supply);

	@Override
	public void contextStarted(DataContext ctx) {
		val.push(createValue(supply));
	}

	@Override
	public void contextDestroyed(DataContext ctx) {
		if (val.isEmpty()) return;

		val.pop().applyAll(disposal);
	}

	@Override
	public void destroy() {
		while (!val.isEmpty()) {
			val.pop().applyAll(disposal);
		}

		Hardware.getLocalHardware().removeContextListener(this);
	}

	@Override
	public Console console() { return Hardware.console; }
}
