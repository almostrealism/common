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

import java.util.Optional;

/** The Computer interface. */
public interface Computer<B> {
	/** Performs the getContext operation. */
	ComputeContext<B> getContext(Computation<?> c);

	/** Performs the compileRunnable operation. */
	Runnable compileRunnable(Computation<Void> c);

	/** Performs the compileProducer operation. */
	<T extends B> Evaluable<T> compileProducer(Computation<T> c);

	/** Performs the decompile operation. */
	<T> Optional<Computation<T>> decompile(Runnable r);

	/** Performs the decompile operation. */
	<T> Optional<Computation<T>> decompile(Evaluable<T> p);
}
