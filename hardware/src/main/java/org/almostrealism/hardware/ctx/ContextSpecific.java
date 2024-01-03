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
import io.almostrealism.uml.SuppliedValue;
import org.almostrealism.hardware.Hardware;

import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class ContextSpecific<T> implements ContextListener {
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
			System.out.println("WARN: " + val.size() + " context layers for " + v.getClass().getSimpleName());
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

		SuppliedValue<T> v = val.pop();
		// TODO  This is disposing only the value on the current thread, not for *all* threads
		if (disposal != null && v.isAvailable()) disposal.accept(v.getValue());
	}
}
