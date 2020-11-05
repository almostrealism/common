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

package org.almostrealism.hardware;

import org.almostrealism.relation.Computation;
import org.almostrealism.util.Producer;

import java.util.Optional;

public class DefaultComputer implements Computer<MemWrapper> {

	@Override
	public Runnable compileRunnable(Computation<Void> c) {
		return new AcceleratedComputationOperation<>(c, true);
	}

	@Override
	public <T extends MemWrapper> Producer<T> compileProducer(Computation<T> c) {
		return new AcceleratedComputationProducer<>(c);
	}

	@Override
	public <T> Optional<Computation<T>> decompile(Runnable r) {
		if (r instanceof AcceleratedComputationOperation) {
			return Optional.of(((AcceleratedComputationOperation) r).getComputation());
		} else {
			return Optional.empty();
		}
	}

	@Override
	public <T> Optional<Computation<T>> decompile(Producer<T> p) {
		if (p instanceof AcceleratedComputationProducer) {
			return Optional.of(((AcceleratedComputationProducer) p).getComputation());
		} else {
			return Optional.empty();
		}
	}
}
