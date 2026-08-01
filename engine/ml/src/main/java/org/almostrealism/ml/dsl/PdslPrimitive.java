/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml.dsl;

import org.almostrealism.model.Block;

import java.util.List;
import java.util.function.Function;

import io.almostrealism.collect.TraversalPolicy;

/**
 * Dispatcher for a PDSL primitive function call.
 *
 * <p>{@link PdslInterpreter} resolves an unknown function call name by looking it
 * up in its registered-primitive map. Higher-level modules (for example the audio
 * domain in {@code studio/compose}) supply their primitives by calling
 * {@link PdslInterpreter#registerPrimitive(String, PdslPrimitive)}; the interpreter
 * core therefore stays free of any audio-domain dispatch.</p>
 *
 * <p>The {@link PdslPrimitiveContext} parameter exposes the per-call interpretation
 * environment ({@code channels}, {@code signal_size}) and the single
 * {@link PdslPrimitiveContext#toProducer toProducer} normalisation used to convert
 * argument values from {@code Number}, {@code PackedCollection}, or {@code Producer}
 * to a uniform {@code Producer<PackedCollection>}. Primitives must use that
 * normalisation rather than reimplementing the dispatch — the registry boundary is
 * the only place where non-Producer arguments are permitted.</p>
 *
 * <p>The type parameter {@code T} declares what the primitive produces. The
 * interpreter core consumes results uniformly via {@link Object} (so the registry
 * stays heterogeneous), but each primitive is itself typed so the implementor and
 * any direct caller see the concrete result type. Most primitives produce a
 * {@link Block} or a {@link Function}{@code <TraversalPolicy, Block>} factory; some
 * produce other types (e.g. a numeric literal or {@link TraversalPolicy}).</p>
 *
 * @param <T> the result type produced by this primitive's
 *            {@link #dispatch dispatch} method
 */
@FunctionalInterface
public interface PdslPrimitive<T> {

	/**
	 * Build the result of a single primitive call.
	 *
	 * @param args the evaluated argument list from the PDSL source
	 * @param ctx  the per-call context (channels, signal size, normalisation)
	 * @return the result the interpreter appends to the surrounding block
	 */
	T dispatch(List<Object> args, PdslPrimitiveContext ctx);
}
