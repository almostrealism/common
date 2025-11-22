/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.relation;

/**
 * An interface for tracking the origin of generated data or objects.
 *
 * <p>{@link Generated} provides a mechanism to maintain a reference from
 * generated data back to its source (generator). This enables tracing
 * the provenance of computed or transformed values.</p>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Tracking which {@link Producer} generated a result</li>
 *   <li>Debugging computation pipelines</li>
 *   <li>Maintaining audit trails for transformations</li>
 *   <li>Caching based on generator identity</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * class ComputedResult implements Generated<Producer<?>, ComputedResult> {
 *     private final Producer<?> source;
 *     private final Object result;
 *
 *     ComputedResult(Producer<?> source, Object result) {
 *         this.source = source;
 *         this.result = result;
 *     }
 *
 *     @Override
 *     public Producer<?> getGenerator() { return source; }
 * }
 * }</pre>
 *
 * @param <T> the type of the generator
 * @param <V> the type of the generated object (typically the implementing class)
 *
 * @author Michael Murray
 */
public interface Generated<T, V> {
	/**
	 * Returns the generator that produced this object.
	 *
	 * @return the generator
	 */
	T getGenerator();

	/**
	 * Returns the generated object.
	 *
	 * <p>The default implementation returns {@code this}, assuming the
	 * implementing class is itself the generated object.</p>
	 *
	 * @return the generated object
	 */
	default V getGenerated() {
		return (V) this;
	}
}
