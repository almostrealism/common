/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware.instructions;

/**
 * Marker interface for operation cache keys in {@link InstructionSetManager}.
 *
 * <p>{@link ExecutionKey} serves as a type-safe identifier for retrieving compiled {@link io.almostrealism.code.Execution}
 * instances from an {@link InstructionSetManager}. Different implementations provide different identification strategies:</p>
 *
 * <ul>
 *   <li><strong>{@link DefaultExecutionKey}:</strong> Identifies by function name and argument count</li>
 *   <li><strong>{@link ScopeSignatureExecutionKey}:</strong> Identifies by scope signature string</li>
 *   <li><strong>{@link ProcessTreePositionKey}:</strong> Identifies by position in Process tree hierarchy</li>
 * </ul>
 *
 * <h2>Implementation Requirements</h2>
 *
 * <p>Concrete {@link ExecutionKey} implementations MUST properly implement {@code equals()} and {@code hashCode()}
 * for correct behavior in hash-based caches:</p>
 *
 * <pre>{@code
 * public class CustomExecutionKey implements ExecutionKey {
 *     private String identifier;
 *
 *     @Override
 *     public boolean equals(Object o) {
 *         if (this == o) return true;
 *         if (!(o instanceof CustomExecutionKey)) return false;
 *         return Objects.equals(identifier, ((CustomExecutionKey) o).identifier);
 *     }
 *
 *     @Override
 *     public int hashCode() {
 *         return Objects.hash(identifier);
 *     }
 * }
 * }</pre>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * // Create key for operation
 * ExecutionKey key = new ScopeSignatureExecutionKey("Add_f64_3_2");
 *
 * // Retrieve compiled execution from cache
 * InstructionSetManager<ScopeSignatureExecutionKey> manager = ...;
 * Execution operation = manager.getOperator(key);
 *
 * // Execute operation
 * operation.run(args);
 * }</pre>
 *
 * <h2>Cache Efficiency</h2>
 *
 * <p>Proper key design is critical for cache hit rate:</p>
 * <ul>
 *   <li><strong>Too broad:</strong> Different operations share keys, causing incorrect execution</li>
 *   <li><strong>Too narrow:</strong> Identical operations use different keys, wasting compilation time</li>
 * </ul>
 *
 * <pre>{@code
 * // GOOD: Captures operation identity
 * new DefaultExecutionKey("matmul", 3);  // matmul(A, B, C)
 *
 * // BAD: Too narrow, prevents reuse
 * new CustomKey("matmul_" + System.currentTimeMillis());
 * }</pre>
 *
 * @see InstructionSetManager
 * @see DefaultExecutionKey
 * @see ScopeSignatureExecutionKey
 * @see ProcessTreePositionKey
 */
public interface ExecutionKey {
}
