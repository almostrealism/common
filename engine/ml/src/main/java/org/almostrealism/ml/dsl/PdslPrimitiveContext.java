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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Per-call interpretation context handed to a {@link PdslPrimitive} dispatcher.
 *
 * <p>Two responsibilities:</p>
 * <ul>
 *   <li><b>Environment access</b>. Multi-channel primitives read {@link #channels()}
 *       and {@link #signalSize()} from the surrounding PDSL environment, and
 *       rectangular routing primitives update the current channel count via
 *       {@link #setChannels(int)} so that downstream {@code for each channel},
 *       {@code sum_channels()}, subscript, etc. operate on the new count.</li>
 *   <li><b>Argument normalisation</b>. {@link #toProducer toProducer} is the single
 *       conversion from the heterogeneous PDSL argument types (numeric literal,
 *       {@code PackedCollection}, {@code Producer}) to a uniform
 *       {@code CollectionProducer}. Primitives must call it once
 *       per shaped argument and never inspect the original type — the
 *       registration boundary is the only place where the dispatch is permitted.</li>
 * </ul>
 */
public interface PdslPrimitiveContext {

	/**
	 * Returns the channel count bound in the current PDSL environment.
	 *
	 * @return the {@code channels} binding from the surrounding PDSL environment;
	 *         throws {@link PdslParseException} if the binding is missing.
	 */
	int channels();

	/**
	 * Returns the per-channel sample count bound in the current PDSL environment.
	 *
	 * @return the {@code signal_size} binding from the surrounding PDSL environment;
	 *         throws {@link PdslParseException} if the binding is missing.
	 */
	int signalSize();

	/**
	 * Updates the {@code channels} binding in the current PDSL environment. Used by
	 * rectangular routing primitives whose output channel count differs from the
	 * input channel count, so downstream multi-channel constructs see the new count.
	 *
	 * @param channels the new channel count; must be positive
	 */
	void setChannels(int channels);

	/**
	 * Single point of conversion from a PDSL argument value to a producer of the
	 * declared shape. Accepts:
	 * <ul>
	 *   <li>{@link Number} — folded into the kernel as a constant (only valid when
	 *       {@code expectedShape} has total size 1).</li>
	 *   <li>{@link PackedCollection} of the right shape — wrapped as a slot-backed
	 *       producer so the slot can be mutated between renders.</li>
	 *   <li>{@link Producer}{@code <PackedCollection>} — returned as-is, with shape
	 *       validation against {@code expectedShape}.</li>
	 * </ul>
	 *
	 * <p>Anything else (including {@code null} or a {@code Producer} of the wrong
	 * shape) is rejected with a {@link PdslParseException}.</p>
	 *
	 * @param value         the raw argument as evaluated by the interpreter
	 * @param expectedShape the required shape (e.g. {@code shape(1)} for scalars), or
	 *                      {@code null} to skip shape validation when the primitive
	 *                      accepts variable-length inputs (e.g. FIR coefficient arrays)
	 * @param contextName   human-readable identifier for error messages
	 * @return a {@link CollectionProducer} of the declared shape
	 */
	CollectionProducer toProducer(Object value,
													TraversalPolicy expectedShape,
													String contextName);

	/**
	 * Reads an integer from a PDSL argument value, accepting any boxed numeric.
	 * Shared between the PDSL interpreter and registered primitives so the integer
	 * coercion rule (Number → int via {@code intValue}) has a single definition.
	 *
	 * @param value the argument value as evaluated by the interpreter
	 * @return the integer value
	 * @throws PdslParseException if {@code value} is not a {@link Number}
	 */
	static int toInt(Object value) {
		if (value instanceof Integer) return (Integer) value;
		if (value instanceof Number) return ((Number) value).intValue();
		throw new PdslParseException("Expected int but got " + value);
	}

	/**
	 * Reads a double from a PDSL argument value, accepting any boxed numeric.
	 * Shared between the PDSL interpreter and registered primitives so the
	 * coercion rule (Number → double via {@code doubleValue}) has a single
	 * definition.
	 *
	 * @param value the argument value as evaluated by the interpreter
	 * @return the double value
	 * @throws PdslParseException if {@code value} is not a {@link Number}
	 */
	static double toDouble(Object value) {
		if (value instanceof Number) return ((Number) value).doubleValue();
		throw new PdslParseException("Expected number but got " + value);
	}
}
