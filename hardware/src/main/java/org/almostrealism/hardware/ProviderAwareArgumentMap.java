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

import io.almostrealism.relation.Delegated;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.OutputVariablePreservationArgumentMap;
import io.almostrealism.relation.Provider;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * An argument map that intelligently handles {@link Provider} and {@link PassThroughProducer} lookups
 * by resolving semantic equivalence rather than just object identity.
 *
 * <p>{@link ProviderAwareArgumentMap} extends {@link OutputVariablePreservationArgumentMap} with
 * additional logic to recognize when different argument suppliers represent the same underlying value:
 * <ul>
 *   <li><strong>PassThrough matching:</strong> Finds arguments by argument index, not object identity</li>
 *   <li><strong>Provider matching:</strong> Matches arguments by the value they produce, not the provider object</li>
 *   <li><strong>Output preservation:</strong> Maintains output variable naming (inherited from base class)</li>
 * </ul>
 *
 * <h2>Core Concept: Semantic Argument Matching</h2>
 *
 * <p>In computation graphs, the same logical argument may appear multiple times represented by
 * different objects. {@link ProviderAwareArgumentMap} recognizes these as the same argument:</p>
 *
 * <pre>{@code
 * // Same argument index referenced twice
 * Producer<PackedCollection<?>> input1 = Input.value(1000, 0);  // arg 0
 * Producer<PackedCollection<?>> input2 = Input.value(1000, 0);  // arg 0 (same!)
 *
 * // Without ProviderAwareArgumentMap:
 * map.get(input1) -> variable "arg0"
 * map.get(input2) -> variable "arg1"  // Wrong! Creates duplicate
 *
 * // With ProviderAwareArgumentMap:
 * map.get(input1) -> variable "arg0"
 * map.get(input2) -> variable "arg0"  // Correct! Reuses same variable
 * }</pre>
 *
 * <h2>Matching Strategies</h2>
 *
 * <p>The {@link #get(Supplier, NameProvider)} method uses three matching strategies in order:</p>
 *
 * <h3>1. Identity Match (Base Class)</h3>
 * <pre>{@code
 * // First, try standard identity lookup
 * ArrayVariable<A> arg = super.get(key, p);
 * if (arg != null) return arg;  // Found by identity
 * }</pre>
 *
 * <h3>2. PassThrough Index Match</h3>
 * <pre>{@code
 * // If key is PassThroughProducer (wrapped or direct), match by argument index
 * if (key delegates to PassThroughProducer) {
 *     int index = getReferencedArgumentIndex();
 *     // Find any existing variable with same argument index
 *     return findVariableByArgumentIndex(index);
 * }
 * }</pre>
 *
 * <h3>3. Provider Value Match</h3>
 * <pre>{@code
 * // If key is a Provider, match by the value it produces
 * if (key.get() instanceof Provider) {
 *     Object value = ((Provider) key.get()).get();
 *     // Find any existing variable that produces same value
 *     return findVariableByProducedValue(value);
 * }
 * }</pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Deduplication of Input References</h3>
 * <pre>{@code
 * ProviderAwareArgumentMap<PackedCollection<?>, Double> map = new ProviderAwareArgumentMap<>();
 *
 * // Multiple references to arg 0
 * Producer<PackedCollection<?>> a = Input.value(1000, 0);
 * Producer<PackedCollection<?>> b = Input.value(1000, 0);
 * Producer<PackedCollection<?>> c = Input.value(1000, 0);
 *
 * ArrayVariable<?> var1 = map.get(a, nameProvider);  // "arg0"
 * ArrayVariable<?> var2 = map.get(b, nameProvider);  // "arg0" (reused!)
 * ArrayVariable<?> var3 = map.get(c, nameProvider);  // "arg0" (reused!)
 *
 * // All three map to same variable, preventing duplicates
 * }</pre>
 *
 * <h3>Provider Deduplication</h3>
 * <pre>{@code
 * // Same data provider wrapped differently
 * PackedCollection<?> data = new PackedCollection<>(1000);
 * Producer<PackedCollection<?>> p1 = () -> data;
 * Producer<PackedCollection<?>> p2 = () -> data;
 *
 * ArrayVariable<?> var1 = map.get(p1, nameProvider);  // "data"
 * ArrayVariable<?> var2 = map.get(p2, nameProvider);  // "data" (same!)
 *
 * // Both producers map to same variable since they produce the same value
 * }</pre>
 *
 * <h3>Computation Graph Optimization</h3>
 * <pre>{@code
 * // Operation uses same input multiple times
 * Producer<PackedCollection<?>> input = Input.value(1000, 0);
 * Producer<PackedCollection<?>> sum = add(input, input);  // Uses arg 0 twice
 *
 * // During kernel compilation:
 * ProviderAwareArgumentMap map = new ProviderAwareArgumentMap<>();
 * ArrayVariable<?> left = map.get(input, nameProvider);   // "arg0"
 * ArrayVariable<?> right = map.get(input, nameProvider);  // "arg0" (same!)
 *
 * // Generated kernel:
 * // __kernel void add(__global double* arg0, __global double* output) {
 * //     output[i] = arg0[i] + arg0[i];  // Single arg0, not arg0 + arg1
 * // }
 * }</pre>
 *
 * <h2>PassThrough Delegation Chain</h2>
 *
 * <p>Handles delegation chains where {@link PassThroughProducer}s are wrapped:</p>
 * <pre>
 * Wrapper Producer
 *     v delegates to
 * PassThroughProducer (index=0)
 *
 * Map recognizes: Wrapper -> PassThrough -> argument index 0
 * </pre>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * PassThroughProducer passThrough = new PassThroughProducer(shape, 0);
 * Producer<?> wrapper = new DelegatingProducer(passThrough);
 *
 * // Map recognizes wrapper delegates to argument 0
 * ArrayVariable<?> var = map.get(wrapper, nameProvider);  // "arg0"
 * }</pre>
 *
 * <h2>Integration with Kernel Compilation</h2>
 *
 * <p>During kernel compilation, {@link ProviderAwareArgumentMap} is used to:
 * <ul>
 *   <li>Map computation arguments to kernel parameters</li>
 *   <li>Eliminate duplicate parameter declarations</li>
 *   <li>Optimize kernel argument lists</li>
 *   <li>Preserve output variable names across optimizations</li>
 * </ul>
 *
 * <h2>Performance Benefits</h2>
 *
 * <ul>
 *   <li><strong>Reduced kernel parameters:</strong> Fewer arguments means faster kernel invocation</li>
 *   <li><strong>Smaller argument buffers:</strong> Less memory transfer to/from GPU</li>
 *   <li><strong>Cache efficiency:</strong> Reusing variables improves register allocation</li>
 * </ul>
 *
 * <h2>Limitations</h2>
 *
 * <p>Value-based matching for {@link Provider}s uses reference equality ({@code ==}):
 * <ul>
 *   <li>Only detects providers that return the exact same object instance</li>
 *   <li>Does not detect semantically equivalent but different object instances</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Not thread-safe. Typically used during single-threaded kernel compilation.</p>
 *
 * @param <S> The type of scope (e.g., {@link io.almostrealism.scope.Scope})
 * @param <A> The type of array elements (e.g., {@code Double})
 *
 * @see OutputVariablePreservationArgumentMap
 * @see Provider
 * @see PassThroughProducer
 * @see Delegated
 */
public class ProviderAwareArgumentMap<S, A> extends OutputVariablePreservationArgumentMap<S, A> implements ConsoleFeatures {
	@Override
	public ArrayVariable<A> get(Supplier key, NameProvider p) {
		ArrayVariable<A> arg = super.get(key, p);
		if (arg != null) return arg;

		if (key instanceof Delegated<?> && ((Delegated) key).getDelegate() instanceof PassThroughProducer<?>) {
			PassThroughProducer param = (PassThroughProducer) ((Delegated) key).getDelegate();

			Optional<ArrayVariable<A>> passThrough = get(v -> {
				if (!(v instanceof Delegated<?>)) return false;
				if (!(((Delegated) v).getDelegate() instanceof PassThroughProducer)) return false;
				return ((PassThroughProducer) ((Delegated) v).getDelegate()).getReferencedArgumentIndex() == param.getReferencedArgumentIndex();
			}, p);

			if (passThrough.isPresent())
				return passThrough.get();
		}

		Object provider = key.get();
		if (!(provider instanceof Provider)) return null;

		Object value = ((Provider) provider).get();

		return get(supplier -> {
			Object v = supplier.get();
			if (!(v instanceof Provider)) return false;
			return ((Provider) v).get() == value;
		}, p).orElse(null);
	}

	@Override
	public Console console() { return Hardware.console; }
}
