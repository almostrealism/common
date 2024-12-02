/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.lifecycle.SuppliedValue;

import java.util.function.Consumer;

public class CachedValue<T> extends SuppliedValue<T> implements Evaluable<T> {
	private Producer<T> source;
	private Evaluable<T> eval;

	public CachedValue(Producer<T> source) {
		this.source = source;
	}

	public CachedValue(Evaluable<T> source) {
		this(source, null);
	}

	public CachedValue(Evaluable<T> source, Consumer<T> clear) {
		setEvaluable(source);
		setClear(clear);
	}

	@Override
	protected T createValue() {
		return createValue(new Object[0]);
	}

	protected T createValue(Object args[]) {
		if (eval == null) eval = source.get();
		return eval.evaluate(args);
	}

	protected void setEvaluable(Evaluable<T> eval) {
		this.eval = eval;
	}

	@Override
	public T evaluate(Object... args) {
		if (!isAvailable()) value = createValue(args);
		return getValue();
	}
}
