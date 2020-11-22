/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.util;

import org.almostrealism.algebra.Triple;
import org.almostrealism.relation.TripleFunction;

/**
 * The {@link AdaptEvaluable} provides a way for a {@link Evaluable}
 * to accept {@link Evaluable}s of its arguments instead of the arguments
 * directly. The resulting {@link Evaluable} instead accepts whatever
 * arguments those supplied {@link Evaluable}s accept. This only works
 * if all the supplied producers accept the exact same arguments in
 * the same order.
 *
 * @author  Michael Murray
 */
public class AdaptEvaluable<T> implements Evaluable<T> {
	private Evaluable<T> p;
	private Evaluable args[];

	public AdaptEvaluable(Evaluable<T> p, Evaluable... args) {
		this.p = p;
		this.args = args;
	}

	@Override
	public T evaluate(Object[] arguments) {
		Object values[] = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			values[i] = args[i].evaluate(arguments);
		}

		return p.evaluate(values);
	}

	@Override
	public void compact() {
		this.p.compact();
		for (Evaluable arg : args) arg.compact();
	}

	public static <T extends Triple, V> AdaptEvaluable<V> fromFunction(TripleFunction<T, V> f, Evaluable<? extends Triple> in) {
		return new AdaptEvaluable<>(new DynamicEvaluable<>(args -> f.operate((T) args[0])), in);
	}
}
