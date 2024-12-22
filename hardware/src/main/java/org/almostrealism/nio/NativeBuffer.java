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

package org.almostrealism.nio;

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.RAM;

import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class NativeBuffer extends RAM implements Destroyable {
	private final NativeBufferMemoryProvider provider;
	private final ByteBuffer rootBuffer;
	private final Buffer buffer;
	private final String sharedLocation;
	private List<Consumer<NativeBuffer>> deallocationListeners;

	protected NativeBuffer(NativeBufferMemoryProvider provider,
						   ByteBuffer rootBuffer, Buffer buffer,
						   String sharedLocation) {
		if (!rootBuffer.isDirect() || !buffer.isDirect())
			throw new UnsupportedOperationException();
		this.provider = provider;
		this.rootBuffer = rootBuffer;
		this.buffer = buffer;
		this.sharedLocation = sharedLocation;
		this.deallocationListeners = new ArrayList<>();
	}

	@Override
	public MemoryProvider<NativeBuffer> getProvider() { return provider; }

	@Override
	public long getContentPointer() { return NIO.pointerForBuffer(buffer); }

	public Buffer getBuffer() { return buffer; }

	public void sync() {
		if (sharedLocation != null) {
			NIO.syncSharedMemory(rootBuffer, rootBuffer.capacity());
		}
	}

	@Override
	public void destroy() {
		if (sharedLocation != null) {
			NIO.unmapSharedMemory(rootBuffer, rootBuffer.capacity());
		}
	}

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

	protected static ByteBuffer buffer(int bytes, String sharedLocation) {
		if (sharedLocation != null) {
			ByteBuffer buffer = NIO.mapSharedMemory(sharedLocation, bytes)
					.order(ByteOrder.nativeOrder());
			Runtime.getRuntime().addShutdownHook(
					new Thread(() -> new File(sharedLocation).delete()));
			return buffer;
		} else {
			return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
		}
	}

	public static NativeBuffer create(NativeBufferMemoryProvider provider, int len, String sharedLocation) {
		if (provider.getPrecision() == Precision.FP16) {
			ByteBuffer bufferByte = buffer(len * 2, sharedLocation);
			return new NativeBuffer(provider, bufferByte, bufferByte.asShortBuffer(), sharedLocation);
		} else if (provider.getPrecision() == Precision.FP32) {
			ByteBuffer bufferByte = buffer(len * 4, sharedLocation);
			return new NativeBuffer(provider, bufferByte, bufferByte.asFloatBuffer(), sharedLocation);
		} else if (provider.getPrecision() == Precision.FP64) {
			ByteBuffer bufferByte = buffer(len * 8, sharedLocation);
			return new NativeBuffer(provider, bufferByte, bufferByte.asDoubleBuffer(), sharedLocation);
		} else {
			throw new HardwareException("Unsupported precision");
		}
	}
}
