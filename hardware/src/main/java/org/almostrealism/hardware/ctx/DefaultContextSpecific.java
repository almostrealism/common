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

import org.almostrealism.lifecycle.SuppliedValue;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class DefaultContextSpecific<T> extends ContextSpecific<T> {
	private Predicate<T> valid;

	public DefaultContextSpecific(Supplier<T> supply) {
		super(supply);
	}

	public DefaultContextSpecific(Supplier<T> supply, Consumer<T> disposal) {
		super(supply, disposal);
	}

	public void setValid(Predicate<T> valid) {
		this.valid = valid;
	}

	@Override
	public SuppliedValue createValue(Supplier supply) {
		SuppliedValue v = new SuppliedValue(supply);
		v.setValid(valid);
		return v;
	}
}
