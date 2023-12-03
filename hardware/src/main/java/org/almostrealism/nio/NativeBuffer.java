/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.nio;

import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.RAM;

import java.nio.Buffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class NativeBuffer extends RAM {
	private NativeBufferMemoryProvider provider;
	private Buffer buffer;
	private List<Consumer<NativeBuffer>> deallocationListeners;

	protected NativeBuffer(NativeBufferMemoryProvider provider, Buffer buffer) {
		if (!buffer.isDirect())
			throw new UnsupportedOperationException();
		this.provider = provider;
		this.buffer = buffer;
		this.deallocationListeners = new ArrayList<>();
	}

	@Override
	public MemoryProvider<NativeBuffer> getProvider() { return provider; }

	@Override
	public long getContentPointer() { return NIO.pointerForBuffer(buffer); }

	public Buffer getBuffer() { return buffer; }

	@Override
	public long getSize() {
		return provider.getNumberSize() * (long) buffer.capacity();
	}

	public void addDeallocationListener(Consumer<NativeBuffer> listener) {
		deallocationListeners.add(listener);
	}

	public List<Consumer<NativeBuffer>> getDeallocationListeners() {
		return deallocationListeners;
	}
}
