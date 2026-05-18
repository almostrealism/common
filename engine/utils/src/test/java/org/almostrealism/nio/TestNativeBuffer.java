/*
 * Copyright 2026 Michael Murray
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Test-only {@link NativeBuffer} subclass that avoids the {@link NIO} native library
 * dependency by overriding {@link #getContentPointer()} with a fixed address.
 *
 * <p>This class accesses the {@code protected} {@link NativeBuffer} constructor,
 * which is why it must reside in the {@code org.almostrealism.nio} package.</p>
 */
public class TestNativeBuffer extends NativeBuffer {
	private final long address;

	private TestNativeBuffer(ByteBuffer rootBuffer, String sharedLocation, long address) {
		super(null, rootBuffer, rootBuffer.asDoubleBuffer(), sharedLocation);
		this.address = address;
	}

	/**
	 * Creates a test buffer with the specified shared location and fixed address.
	 *
	 * @param sharedLocation the shared memory path, or {@code null} for non-shared
	 * @param address the fixed address to return from {@link #getContentPointer()}
	 * @param size the buffer size in bytes
	 * @return a test NativeBuffer instance
	 */
	public static TestNativeBuffer create(String sharedLocation, long address, int size) {
		ByteBuffer rootBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
		return new TestNativeBuffer(rootBuffer, sharedLocation, address);
	}

	@Override
	public long getContentPointer() { return address; }

	@Override
	public long getSize() { return 1024; }
}
