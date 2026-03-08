/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.hardware.mem;

import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * Caching factory for {@link MemoryBank} instances with automatic reuse and cleanup.
 *
 * <p>{@link MemoryBankProvider} provides a size-based factory that caches the most recently
 * allocated {@link MemoryBank} and reuses it for subsequent requests of the same size. This
 * reduces allocation overhead when repeatedly requesting banks of identical size.</p>
 *
 * <h2>Caching Behavior</h2>
 *
 * <pre>{@code
 * MemoryBankProvider<Bytes> provider = new MemoryBankProvider<>(
 *     size -> new BytesBank(size, 100)  // 100 entries of 'size' bytes each
 * );
 *
 * // First request: Allocates new bank
 * MemoryBank<Bytes> bank1 = provider.apply(1000);  // Allocates
 *
 * // Same size: Returns cached bank
 * MemoryBank<Bytes> bank2 = provider.apply(1000);  // Reuses bank1
 * assert bank1 == bank2;  // Same instance
 *
 * // Different size: Destroys cached, allocates new
 * MemoryBank<Bytes> bank3 = provider.apply(2000);  // Destroys bank1, allocates new
 * }</pre>
 *
 * <h2>Automatic Cleanup</h2>
 *
 * <p>When a different size is requested, the cached bank is automatically destroyed before
 * allocating the new one:</p>
 * <pre>{@code
 * MemoryBank<Bytes> bank1 = provider.apply(1000);  // Allocate
 * // bank1 is cached
 *
 * MemoryBank<Bytes> bank2 = provider.apply(2000);  // Destroy bank1, allocate new
 * // bank1.destroy() called automatically
 * // bank2 is now cached
 * }</pre>
 *
 * <h2>Custom Supplier with State</h2>
 *
 * <p>Advanced constructor accepts a {@link BiFunction} that receives both the previous bank
 * and new size, enabling stateful allocation strategies:</p>
 * <pre>{@code
 * MemoryBankProvider<Bytes> provider = new MemoryBankProvider<>(
 *     (previous, newSize) -> {
 *         if (previous != null) {
 *             // Custom cleanup logic
 *             recycleBank(previous);
 *         }
 *         return newSize > 0 ? allocateBank(newSize) : null;
 *     }
 * );
 * }</pre>
 *
 * <h2>Explicit Destruction</h2>
 *
 * <p>Call {@link #destroy()} to explicitly release the cached bank:</p>
 * <pre>{@code
 * MemoryBankProvider<Bytes> provider = new MemoryBankProvider<>(...);
 * MemoryBank<Bytes> bank = provider.apply(1000);
 *
 * // Later, when done with provider
 * provider.destroy();  // Destroys cached bank
 * }</pre>
 *
 * @param <T> MemoryData type for bank elements
 * @see MemoryBank
 * @see MemoryBankAdapter
 */
public class MemoryBankProvider<T extends MemoryData> implements IntFunction<MemoryBank<T>>, ConsoleFeatures {
	private BiFunction<MemoryBank<T>, Integer, MemoryBank<T>> supplier;
	private MemoryBank<T> last;
	private int lastSize;

	public MemoryBankProvider(IntFunction<MemoryBank<T>> supplier) {
		this((v, i) -> {
			if (v != null) v.destroy();
			return i != null && i > 0 ? supplier.apply(i) : null;
		});
	}

	public MemoryBankProvider(BiFunction<MemoryBank<T>, Integer, MemoryBank<T>> supplier) {
		this.supplier = supplier;
	}

	protected void updateLast(int size) {
		last = supplier.apply(last, size);
		lastSize = size;
	}

	public MemoryBank<T> apply(int size) {
		if (lastSize == size && last != null && last.getMem() != null) {
			return last;
		}

		updateLast(size);
		return last;
	}

	public void destroy() {
		updateLast(0);
	}

	@Override
	public Console console() { return Hardware.console; }
}
