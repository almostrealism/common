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

package io.almostrealism.code;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;

import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * The {@link AdaptProducer} provides a way for a {@link Producer}
 * to accept {@link Producer}s of its arguments instead of the arguments
 * directly. The resulting {@link Producer} instead accepts whatever
 * arguments those supplied {@link Producer}s accept. This only works
 * if all the supplied producers accept the exact same arguments in
 * the same order.
 *
 * @author  Michael Murray
 */
public class AdaptProducer<T> implements Producer<T>, ScopeLifecycle {
	private Producer<T> p;
	private Producer args[];

	public AdaptProducer(Producer<T> p, Producer... args) {
		this.p = p;
		this.args = args;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		ScopeLifecycle.prepareArguments(Stream.of(p), map);
		ScopeLifecycle.prepareArguments(Stream.of(args), map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		ScopeLifecycle.prepareScope(Stream.of(p), manager);
		ScopeLifecycle.prepareScope(Stream.of(args), manager);
	}

	@Override
	public void resetArguments() {
		ScopeLifecycle.resetArguments(Stream.of(p));
		ScopeLifecycle.resetArguments(Stream.of(args));
	}

	@Override
	public Evaluable<T> get() {
		Evaluable<T> ev = p.get();
		Evaluable ar[] = new Evaluable[args.length];

		IntStream.range(0, ar.length).forEach(i -> ar[i] = args[i].get());

		return arguments -> {
			Object values[] = new Object[args.length];
			for (int i = 0; i < args.length; i++) {
				values[i] = ar[i].evaluate(arguments);
			}

			return ev.evaluate(values);
		};
	}

	@Override
	public void compact() {
		this.p.compact();
		for (Producer arg : args) arg.compact();
	}
}
