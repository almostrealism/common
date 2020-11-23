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

package org.almostrealism.color.computations;

import io.almostrealism.code.Scope;
import org.almostrealism.algebra.Triple;
import org.almostrealism.color.RGB;
import org.almostrealism.relation.Computation;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.relation.Producer;
import org.almostrealism.relation.TripleFunction;
import org.almostrealism.algebra.Vector;
import org.almostrealism.util.DynamicProducer;
import org.almostrealism.util.Generated;

public class GeneratedColorProducer<T> extends ColorProducerAdapter implements Generated<T> {
	private Producer<RGB> p;
	private T generator;

	protected GeneratedColorProducer(T generator) {
		this.generator = generator;
	}

	protected GeneratedColorProducer(T generator, Producer<RGB> p) {
		this.generator = generator;
		this.p = p;
	}

	public T getGenerator() { return generator; }

	public Producer<RGB> getProducer() {
		return p;
	}

	@Override
	public Scope<RGB> getScope(NameProvider provider) {
		return ((Computation) p).getScope(provider);
	}

	public static <T> GeneratedColorProducer<T> fromFunction(T generator, TripleFunction<Triple, RGB> t) {
		return new GeneratedColorProducer(generator, new DynamicProducer<>(args ->
				t.operate(args.length > 0 ? (Triple) args[0] : new Vector(1.0, 1.0, 1.0))));
	}

	public static <T> GeneratedColorProducer<T> fromProducer(T generator, Producer<? extends RGB> p) {
		return new GeneratedColorProducer(generator, p);
	}

	@Override
	public void compact() {
		p.compact();
	}
}
