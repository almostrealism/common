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

package org.almostrealism.hardware;

import io.almostrealism.code.Memory;
import io.almostrealism.collect.TraversalOrdering;

/**
 * A null object implementation of {@link MemoryData} that performs no operations and holds no data.
 *
 * <p>{@link NoOpMemoryData} implements the Null Object pattern for {@link MemoryData}, providing
 * safe no-op implementations of all methods. This is useful for:
 * <ul>
 *   <li><strong>Optional outputs:</strong> When an operation may or may not produce output</li>
 *   <li><strong>Testing:</strong> Placeholder for operations that don't need real memory</li>
 *   <li><strong>Default values:</strong> Safe default when no memory data is available</li>
 * </ul>
 *
 * <h2>Null Object Pattern</h2>
 *
 * <p>Instead of using {@code null} references which require null checks:</p>
 * <pre>{@code
 * // BAD: Requires null checks
 * MemoryData data = maybeGetData();
 * if (data != null) {
 *     data.setMem(values);
 * }
 * }</pre>
 *
 * <p>Use {@link NoOpMemoryData} to eliminate null checks:</p>
 * <pre>{@code
 * // GOOD: No null checks needed
 * MemoryData data = maybeGetData();  // Returns NoOpMemoryData if no data
 * data.setMem(values);  // Safe no-op if NoOpMemoryData
 * }</pre>
 *
 * <h2>Behavior</h2>
 *
 * <p>All methods are no-ops or return safe default values:
 * <ul>
 *   <li><strong>{@link #getMem()}:</strong> Returns {@code null}</li>
 *   <li><strong>{@link #getMemLength()}:</strong> Returns 0</li>
 *   <li><strong>{@link #setDelegate(MemoryData, int, TraversalOrdering)}:</strong> No-op (ignored)</li>
 *   <li><strong>{@link #reassign(Memory)}:</strong> No-op (ignored)</li>
 *   <li><strong>{@link #destroy()}:</strong> No-op (safe to call multiple times)</li>
 * </ul>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Optional Output Parameters</h3>
 * <pre>{@code
 * public MemoryData processWithOptionalDebug(boolean debug) {
 *     MemoryData debugOutput = debug ? new PackedCollection<>(1000) : new NoOpMemoryData();
 *
 *     for (int i = 0; i < 1000; i++) {
 *         double result = compute(i);
 *         debugOutput.setMem(i, result);  // No-op if NoOpMemoryData
 *     }
 *
 *     return debugOutput;
 * }
 * }</pre>
 *
 * <h3>Factory Method with Fallback</h3>
 * <pre>{@code
 * public MemoryData loadData(String filename) {
 *     try {
 *         return loadFromFile(filename);
 *     } catch (IOException e) {
 *         log("Failed to load " + filename);
 *         return new NoOpMemoryData();  // Safe fallback
 *     }
 * }
 * }</pre>
 *
 * <h3>Testing Without Real Memory</h3>
 * <pre>{@code
 * @Test
 * public void testOperationStructure() {
 *     // Test operation structure without allocating real memory
 *     MemoryData mockOutput = new NoOpMemoryData();
 *
 *     Operation op = new MyOperation(mockOutput);
 *     // Test operation construction, validation, etc.
 *     // No actual memory allocation occurs
 * }
 * }</pre>
 *
 * <h2>Memory Safety</h2>
 *
 * <p>{@link NoOpMemoryData} is completely safe:
 * <ul>
 *   <li>No memory is allocated</li>
 *   <li>No resources need to be freed</li>
 *   <li>Calling {@link #destroy()} multiple times is safe</li>
 *   <li>All write operations are silently ignored</li>
 *   <li>All read operations return safe defaults (0.0, empty arrays, etc.)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>{@link NoOpMemoryData} is thread-safe since it holds no state and all operations are no-ops.</p>
 *
 * <h2>Integration with Operations</h2>
 *
 * <p>Operations that accept {@link MemoryData} should handle {@link NoOpMemoryData} gracefully:
 * <ul>
 *   <li>Check {@code getMem() == null} to detect no-op data</li>
 *   <li>Skip processing if detected</li>
 *   <li>Or allow no-op behavior to propagate naturally</li>
 * </ul>
 *
 * @see MemoryData
 */
public class NoOpMemoryData implements MemoryData {
	@Override
	public Memory getMem() { return null; }

	@Override
	public void reassign(Memory mem) { }

	@Override
	public int getMemLength() {
		return 0;
	}

	@Override
	public void setDelegate(MemoryData m, int offset, TraversalOrdering order) { }

	@Override
	public MemoryData getDelegate() { return null; }

	@Override
	public int getDelegateOffset() { return 0; }

	@Override
	public TraversalOrdering getDelegateOrdering() { return null; }

	@Override
	public void destroy() { }
}
