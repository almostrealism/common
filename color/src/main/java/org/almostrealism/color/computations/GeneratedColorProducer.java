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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.Computation;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.TripleFunction;
import io.almostrealism.relation.Generated;
import org.almostrealism.algebra.Triple;
import org.almostrealism.color.RGB;
import org.almostrealism.algebra.Vector;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;

public class GeneratedColorProducer<T> extends ColorProducerAdapter implements Generated<T, Producer<RGB>> {
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

	public Producer<RGB> getGenerated() { return p; }

	@Override
	public void prepareArguments(ArgumentMap map) {
		if (p instanceof Computation) {
			((Computation) p).prepareArguments(map);
		}
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		if (p instanceof Computation) {
			((Computation) p).prepareScope(manager);
		}
	}

	@Override
	public Scope<RGB> getScope() { return ((Computation) p).getScope(); }

	@Override
	public void compact() { p.compact(); }

	@Override
	public KernelizedEvaluable<RGB> get() { return (KernelizedEvaluable<RGB>) p.get(); }

	public static <T> GeneratedColorProducer<T> fromFunction(T generator, TripleFunction<Triple, RGB> t) {
		return new GeneratedColorProducer(generator, new DynamicProducerForMemoryData<>(args ->
				t.operate(args.length > 0 ? (Triple) args[0] : new Vector(1.0, 1.0, 1.0))));
	}

	public static <T> GeneratedColorProducer<T> fromProducer(T generator, Producer<? extends RGB> p) {
		return new GeneratedColorProducer(generator, p);
	}
}
