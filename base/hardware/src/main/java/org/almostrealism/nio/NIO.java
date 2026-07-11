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

import io.almostrealism.code.Precision;
import org.almostrealism.hardware.jni.NativeCompiler;

import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * Native I/O helpers for shared-memory mapping and direct-buffer pointer extraction, used by
 * {@link NativeMemoryProvider}.
 *
 * <p>The underlying operations are compiled at runtime by {@link NativeCompiler} — the same way
 * {@link org.almostrealism.c.Malloc} and the other native operations are — rather than loaded from a
 * prebuilt platform library. As a result they need no {@code libNIO} artifact and work on any
 * platform the native backend supports. The operations are compiled lazily on first use.</p>
 */
public class NIO {
	/** Precision for the helper compiler; the operations perform no numeric work, so any value is fine. */
	private static final Precision PRECISION = Precision.FP64;

	/** Lazily created compiler used to build the native operations. */
	private static volatile NativeCompiler compiler;
	/** Lazily compiled direct-buffer pointer operation. */
	private static volatile NativeBufferPointer pointer;
	/** Lazily compiled shared-memory map operation. */
	private static volatile SharedMemoryMap map;
	/** Lazily compiled shared-memory sync operation. */
	private static volatile SharedMemorySync sync;
	/** Lazily compiled shared-memory unmap operation. */
	private static volatile SharedMemoryUnmap unmap;

	/** Prevents instantiation; all members are static. */
	private NIO() { }

	/** Returns the shared compiler, creating it on first use. */
	private static NativeCompiler compiler() {
		NativeCompiler c = compiler;
		if (c == null) c = initCompiler();
		return c;
	}

	/** Creates the shared compiler under lock if it has not been created yet. */
	private static synchronized NativeCompiler initCompiler() {
		if (compiler == null) compiler = NativeCompiler.factory(PRECISION, false).construct();
		return compiler;
	}

	/** Compiles the pointer operation under lock if it has not been compiled yet. */
	private static synchronized NativeBufferPointer pointer() {
		if (pointer == null) pointer = new NativeBufferPointer(compiler());
		return pointer;
	}

	/** Compiles the shared-memory map operation under lock if it has not been compiled yet. */
	private static synchronized SharedMemoryMap map() {
		if (map == null) map = new SharedMemoryMap(compiler());
		return map;
	}

	/** Compiles the shared-memory sync operation under lock if it has not been compiled yet. */
	private static synchronized SharedMemorySync sync() {
		if (sync == null) sync = new SharedMemorySync(compiler());
		return sync;
	}

	/** Compiles the shared-memory unmap operation under lock if it has not been compiled yet. */
	private static synchronized SharedMemoryUnmap unmap() {
		if (unmap == null) unmap = new SharedMemoryUnmap(compiler());
		return unmap;
	}

	/**
	 * Returns the native memory address of the given direct buffer.
	 *
	 * @param b Direct {@link Buffer} instance
	 * @return Native pointer address for the buffer's backing memory
	 */
	public static long pointerForBuffer(Buffer b) {
		NativeBufferPointer op = pointer;
		if (op == null) op = pointer();
		return op.apply(b);
	}

	/**
	 * Maps a named shared memory region into a direct {@link ByteBuffer}.
	 *
	 * @param filePath Path name for the shared memory region
	 * @param length   Size in bytes to map
	 * @return Direct {@link ByteBuffer} backed by the shared memory region
	 */
	public static ByteBuffer mapSharedMemory(String filePath, int length) {
		SharedMemoryMap op = map;
		if (op == null) op = map();
		return op.apply(filePath, length);
	}

	/**
	 * Flushes changes in a shared memory buffer back to the underlying region.
	 *
	 * @param buffer Shared memory buffer to sync
	 * @param length Number of bytes to sync
	 */
	public static void syncSharedMemory(ByteBuffer buffer, int length) {
		SharedMemorySync op = sync;
		if (op == null) op = sync();
		op.apply(buffer, length);
	}

	/**
	 * Unmaps a shared memory region previously mapped via {@link #mapSharedMemory}.
	 *
	 * @param buffer Shared memory buffer to unmap
	 * @param length Number of bytes to unmap
	 */
	public static void unmapSharedMemory(ByteBuffer buffer, int length) {
		SharedMemoryUnmap op = unmap;
		if (op == null) op = unmap();
		op.apply(buffer, length);
	}
}
