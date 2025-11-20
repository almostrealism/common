/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware.jvm;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;

/**
 * {@link Memory} implementation backed by a Java heap double array.
 *
 * <p>Simplest possible {@link Memory} implementation: wraps a {@code double[]} array
 * allocated on the Java heap. Provides no special features beyond basic array storage.
 * Used by {@link JVMMemoryProvider} for testing and non-accelerated scenarios.</p>
 *
 * <h2>Implementation Details</h2>
 *
 * <ul>
 *   <li><strong>Storage:</strong> {@code double[]} on Java heap</li>
 *   <li><strong>Precision:</strong> FP64 only (8 bytes per element)</li>
 *   <li><strong>Lifecycle:</strong> Garbage collected when unreferenced</li>
 *   <li><strong>Access:</strong> Direct array access (fast, no JNI overhead)</li>
 * </ul>
 *
 * <h2>Memory Layout</h2>
 *
 * <pre>
 * JVMMemory {
 *   provider: JVMMemoryProvider
 *   data: [double_0, double_1, ..., double_N]
 * }
 * </pre>
 *
 * <h2>Performance Characteristics</h2>
 *
 * <ul>
 *   <li><strong>Allocation:</strong> ~0.01ms per allocation (Java heap allocation)</li>
 *   <li><strong>Access:</strong> Direct array access (no overhead)</li>
 *   <li><strong>Transfer:</strong> Array copy (System.arraycopy speed)</li>
 *   <li><strong>GC Impact:</strong> Subject to garbage collection pauses</li>
 * </ul>
 *
 * @see JVMMemoryProvider
 * @see org.almostrealism.hardware.mem.RAM
 */
public class JVMMemory implements Memory {
	private JVMMemoryProvider provider;
	protected double data[];

	public JVMMemory(JVMMemoryProvider provider, int len) {
		this.provider = provider;
		this.data = new double[len];
	}

	@Override
	public MemoryProvider getProvider() { return provider; }

	public void destroy() {
		this.data = null;
	}
}
