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

import io.almostrealism.code.Memory;
import io.almostrealism.code.Precision;
import org.almostrealism.c.Free;
import org.almostrealism.c.Malloc;
import org.almostrealism.c.NativeBufferView;
import org.almostrealism.c.NativeMemory;
import org.almostrealism.c.NativeRead;
import org.almostrealism.c.NativeWrite;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.ByteBufferMemory;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.mem.NativeRef;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.io.DistributionMetric;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.io.TimingMetric;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Memory provider for CPU-side native memory, backed by either NIO direct buffers or JNI malloc.
 *
 * <p>This provider unifies the two ways the framework allocates host-accessible native memory. Both
 * expose their contents as a {@link java.nio.ByteBuffer} (through
 * {@link org.almostrealism.hardware.mem.ByteBufferMemory}), so another backend can move data to and
 * from either in bulk without an intermediate array — for example an OpenCL provider reading or
 * writing the buffer directly:</p>
 *
 * <ul>
 *   <li><b>Direct mode (default)</b> allocates a {@link NativeBuffer} via
 *       {@link java.nio.ByteBuffer#allocateDirect} and supports named shared memory. Its content
 *       pointer is obtained through {@link NIO}, whose operations are compiled at runtime, so it needs
 *       no prebuilt native library.</li>
 *   <li><b>Calloc mode</b> allocates a {@link NativeMemory} via JNI {@link Malloc}; its ByteBuffer
 *       view is produced by a runtime-compiled {@code NewDirectByteBuffer} operation. It avoids the
 *       JVM's direct-memory accounting (and the associated {@code -XX:MaxDirectMemorySize} limit and
 *       {@code System.gc()} pressure).</li>
 * </ul>
 *
 * <p>Both modes work on every platform the native backend supports.</p>
 *
 * <p>The mode is selected per instance and does not change the {@link io.almostrealism.code.MemoryProvider}
 * contract: it only affects how memory is allocated and reclaimed. The default is controlled by
 * {@link #enableDirectAllocation} ({@code AR_HARDWARE_DIRECT_MEMORY}).</p>
 *
 * <h2>Reclamation</h2>
 *
 * <p>Both modes are tracked by {@link HardwareMemoryProvider} for metrics and leak detection, but they
 * are freed differently, and conflating them would double free:</p>
 *
 * <ul>
 *   <li>A {@link NativeBuffer}'s native memory is owned by the JVM's own {@code DirectByteBuffer}
 *       cleaner, which frees it when the buffer is collected. This provider therefore does
 *       <em>not</em> free it in {@link #deallocate(NativeRef)} — it only releases any shared-memory
 *       mapping and runs bookkeeping.</li>
 *   <li>A {@link NativeMemory} block is owned by this provider, which frees it explicitly via JNI
 *       {@link Free} once the referent is collected.</li>
 * </ul>
 *
 * <p>The two cases are distinguished by the reference type: direct allocations are tracked with a
 * {@link NativeBufferRef}, calloc allocations with a plain {@link NativeRef}.</p>
 *
 * <p>Custom allocators and writers can be registered via
 * {@link #registerAdapter(Class, NativeBufferAllocator)} and
 * {@link #registerAdapter(Class, NativeBufferWriter)} to support cross-provider memory access into a
 * {@link NativeBuffer}.</p>
 *
 * @see NativeBuffer
 * @see NativeMemory
 * @see NIO
 * @see HardwareMemoryProvider
 */
public class NativeMemoryProvider extends HardwareMemoryProvider<RAM> {
	/**
	 * Whether native (JNI) execution allocates NIO direct buffers ({@link NativeBuffer}) by default
	 * (the default) rather than JNI {@link Malloc} blocks ({@link NativeMemory}). Both are
	 * host-accessible {@link org.almostrealism.hardware.mem.ByteBufferMemory} and both work on every
	 * platform the native backend supports (the direct-buffer pointer and the calloc ByteBuffer view
	 * are alike produced by runtime-compiled code — no prebuilt library). The difference is only how
	 * memory is allocated and tracked: direct buffers participate in the JVM's
	 * {@code -XX:MaxDirectMemorySize} accounting, calloc blocks do not. Controlled by
	 * {@code AR_HARDWARE_DIRECT_MEMORY}.
	 */
	public static boolean enableDirectAllocation =
			SystemUtils.isEnabled("AR_HARDWARE_DIRECT_MEMORY").orElse(true);

	/**
	 * Enables logging of large memory allocations (greater than 20MB).
	 * Controlled by {@code AR_HARDWARE_ALLOCATION_LOGGING}.
	 */
	public static boolean enableLargeAllocationLogging =
			SystemUtils.isEnabled("AR_HARDWARE_ALLOCATION_LOGGING").orElse(false);

	/** Timing metric tracking native memory I/O operations. */
	public static TimingMetric ioTime = Hardware.console.timing("nativeIO");

	/** Distribution metric tracking native memory allocation sizes in bytes. */
	public static DistributionMetric allocationSizes = Hardware.console.distribution("nativeAllocationSizes", 1024 * 1024);

	/** Distribution metric tracking native memory deallocation sizes in bytes. */
	public static DistributionMetric deallocationSizes = Hardware.console.distribution("nativeDeallocationSizes", 1024 * 1024);

	/** Registered custom allocators for adapting foreign memory types. */
	private static final Map<Class, NativeBufferAllocator> allocationAdapters = new HashMap<>();
	/** Registered custom writers for adapting foreign memory types. */
	private static final Map<Class, NativeBufferWriter> writeAdapters = new HashMap<>();

	/** Numeric precision for element storage in this provider's memory. */
	private final Precision precision;
	/** Maximum number of bytes that may be allocated by this provider at any one time. */
	private final long memoryMax;
	/** If true, new direct allocations use named shared memory when a memory name is available. */
	private final boolean shared;
	/** If true, allocate NIO direct buffers; if false, allocate via JNI malloc. */
	private final boolean direct;

	/** The native compiler used for JNI malloc/free/read/write in calloc mode; may be null in direct mode. */
	private final NativeCompiler compiler;

	/** JNI wrapper for malloc operations (calloc mode). */
	private Malloc malloc;
	/** JNI wrapper for free operations (calloc mode). */
	private Free free;
	/** JNI wrapper for reading from native memory (calloc mode). */
	private NativeRead read;
	/** JNI wrapper for writing to native memory (calloc mode). */
	private NativeWrite write;
	/** JNI wrapper producing a direct buffer view over calloc memory (calloc mode). */
	private NativeBufferView bufferView;

	/** Total bytes currently allocated across all active memory in this provider. */
	private long memoryUsed;

	/**
	 * Creates a NIO direct-buffer provider with shared memory enabled, for the shared-memory bridge.
	 *
	 * <p>Always allocates {@link NativeBuffer} (direct mode), which requires the {@code libNIO}
	 * native library; use a compiler-bearing constructor for the portable calloc default.</p>
	 *
	 * @param precision Numeric precision for elements
	 * @param memoryMax Maximum bytes that may be allocated
	 */
	public NativeMemoryProvider(Precision precision, long memoryMax) {
		this(precision, memoryMax, true, null, true);
	}

	/**
	 * Creates a NIO direct-buffer provider ({@link NativeBuffer}) with optional shared memory.
	 *
	 * @param precision Numeric precision for elements
	 * @param memoryMax Maximum bytes that may be allocated
	 * @param shared    If true, use named shared memory when a memory name is available
	 */
	public NativeMemoryProvider(Precision precision, long memoryMax, boolean shared) {
		this(precision, memoryMax, shared, null, true);
	}

	/**
	 * Creates a provider for native (JNI) execution, using the default allocation mode.
	 *
	 * <p>Shared memory is disabled; the compiler is retained so that calloc mode (when selected)
	 * can generate the JNI malloc/free/read/write operations.</p>
	 *
	 * @param precision Numeric precision for elements
	 * @param memoryMax Maximum bytes that may be allocated
	 * @param compiler  Native compiler for JNI operations in calloc mode
	 */
	public NativeMemoryProvider(Precision precision, long memoryMax, NativeCompiler compiler) {
		this(precision, memoryMax, false, compiler, enableDirectAllocation);
	}

	/**
	 * Creates a provider with an explicit allocation mode.
	 *
	 * @param precision Numeric precision for elements
	 * @param memoryMax Maximum bytes that may be allocated
	 * @param shared    If true, use named shared memory when a memory name is available (direct mode only)
	 * @param compiler  Native compiler for JNI operations in calloc mode; may be null in direct mode
	 * @param direct    If true, allocate NIO direct buffers; if false, allocate via JNI malloc
	 */
	public NativeMemoryProvider(Precision precision, long memoryMax, boolean shared,
								NativeCompiler compiler, boolean direct) {
		this.precision = precision;
		this.memoryMax = memoryMax;
		this.shared = shared;
		this.compiler = compiler;
		this.direct = direct;
	}

	@Override
	public String getName() { return direct ? "NIO" : "JNI"; }

	public Precision getPrecision() { return precision; }

	@Override
	public int getNumberSize() { return getPrecision().bytes(); }

	/** Returns whether this provider allocates NIO direct buffers rather than JNI malloc blocks. */
	public boolean isDirect() { return direct; }

	/** Returns the native compiler for JNI operations, or null if none was provided. */
	public NativeCompiler getNativeCompiler() { return compiler; }

	@Override
	public synchronized RAM allocate(int size) {
		if (memoryUsed + (long) getNumberSize() * size > memoryMax) {
			throw new HardwareException("Memory max reached");
		}

		if (enableLargeAllocationLogging && size > (20 * 1024 * 1024)) {
			log("Allocating " + (getNumberSize() * (long) size) / 1024 / 1024 + "mb");
		}

		RAM mem;
		if (direct) {
			mem = allocated(NativeBuffer.create(this, size, shared ? getMemoryName().apply(size) : null));
		} else {
			if (malloc == null) malloc = new Malloc(getNativeCompiler());
			if (bufferView == null) bufferView = new NativeBufferView(getNativeCompiler());

			long bytes = getNumberSize() * (long) size;
			long pointer = malloc.apply(getNumberSize() * size);
			mem = allocated(new NativeMemory(this, pointer, bytes, bufferView.apply(pointer, 0, bytes)));
		}

		memoryUsed += (long) getNumberSize() * size;
		allocationSizes.addEntry(getNumberSize() * (long) size);
		return mem;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Direct allocations are tracked with a {@link NativeBufferRef} so that
	 * {@link #deallocate(NativeRef)} can distinguish them from calloc allocations and avoid
	 * double-freeing memory owned by the JVM's direct-buffer cleaner.</p>
	 */
	@Override
	protected NativeRef<RAM> nativeRef(RAM ram) {
		if (ram instanceof NativeBuffer) {
			// NativeBufferRef is a NativeRef<NativeBuffer>; this provider tracks NativeRef<RAM>.
			// The parameterizations are unrelated by invariance, so a raw cast is required.
			return (NativeRef) new NativeBufferRef((NativeBuffer) ram, getReferenceQueue());
		}

		return super.nativeRef(ram);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>A direct-buffer allocation (tracked by a {@link NativeBufferRef}) is <em>not</em> freed
	 * here: its native memory is owned by the JVM's {@code DirectByteBuffer} cleaner. Only its
	 * shared-memory mapping is released and its deallocation listeners are notified. A calloc
	 * allocation is freed explicitly via JNI {@link Free}.</p>
	 */
	@Override
	public synchronized void deallocate(NativeRef<RAM> ref) {
		NativeRef<?> tracked = ref;
		if (tracked instanceof NativeBufferRef bufferRef) {
			if (bufferRef.getSharedLocation() != null && bufferRef.getRootBuffer() != null) {
				NIO.unmapSharedMemory(bufferRef.getRootBuffer(), bufferRef.getRootBuffer().capacity());
			}

			bufferRef.getDeallocationListeners().forEach(l -> l.accept(null));
		} else {
			if (free == null) free = new Free(getNativeCompiler());
			free.apply(ref.getAddress());
		}

		memoryUsed -= ref.getSize();
		deallocationSizes.addEntry(ref.getSize());
	}

	@Override
	public RAM reallocate(Memory mem, int offset, int length) {
		if (allocationAdapters.containsKey(mem.getClass())) {
			NativeBuffer buf = allocationAdapters.get(mem.getClass()).allocate(mem, offset, length);
			if (buf != null) return buf;
		}

		RAM newMem = allocate(length);
		setMem(newMem, 0, mem, offset, length);
		return newMem;
	}

	@Override
	public synchronized void setMem(RAM mem, int offset, Memory source, int srcOffset, int length) {
		if (mem instanceof NativeBuffer buffer && source instanceof NativeBuffer sourceBuffer) {
			copyBuffer(buffer, offset, sourceBuffer, srcOffset, length);
			return;
		}

		// A registered adapter can read a foreign source (e.g. an OpenCL buffer) directly into
		// this destination's host buffer, for either the calloc or the NIO backing
		if (mem instanceof ByteBufferMemory buffer && writeAdapters.containsKey(source.getClass())) {
			writeAdapters.get(source.getClass()).setMem(buffer, offset, source, srcOffset, length);
			return;
		}

		double[] value = new double[length];
		source.getProvider().getMem(source, srcOffset, value, 0, length);
		setMem(mem, offset, value, 0, length);
	}

	@Override
	public synchronized void setMem(RAM mem, int offset, double[] source, int srcOffset, int length) {
		if (mem instanceof NativeBuffer buffer) {
			writeBuffer(buffer, offset, source, srcOffset, length);
		} else {
			if (write == null) write = new NativeWrite(getNativeCompiler());

			long start = System.nanoTime();
			try {
				write.apply((NativeMemory) mem, offset, source, srcOffset, length);
			} finally {
				ioTime.addEntry("setMem", System.nanoTime() - start);
			}
		}
	}

	@Override
	public synchronized void getMem(RAM mem, int sOffset, double[] out, int oOffset, int length) {
		if (mem instanceof NativeBuffer buffer) {
			readBuffer(buffer, sOffset, out, oOffset, length);
		} else {
			if (read == null) read = new NativeRead(getNativeCompiler());

			long start = System.nanoTime();
			try {
				read.apply((NativeMemory) mem, sOffset, out, oOffset, length);
			} finally {
				ioTime.addEntry("getMem", System.nanoTime() - start);
			}
		}
	}

	/** Copies elements between two {@link NativeBuffer} instances and flushes shared memory. */
	private void copyBuffer(NativeBuffer mem, int offset, NativeBuffer source, int srcOffset, int length) {
		if (mem.getBuffer() instanceof DoubleBuffer buffer && source.getBuffer() instanceof DoubleBuffer) {
			buffer.put(offset, (DoubleBuffer) source.getBuffer(), srcOffset, length);
		} else if (mem.getBuffer() instanceof FloatBuffer buffer && source.getBuffer() instanceof FloatBuffer) {
			buffer.put(offset, (FloatBuffer) source.getBuffer(), srcOffset, length);
		} else if (mem.getBuffer() instanceof ShortBuffer buffer && source.getBuffer() instanceof ShortBuffer) {
			buffer.put(offset, (ShortBuffer) source.getBuffer(), srcOffset, length);
		} else {
			throw new HardwareException("Unsupported precision");
		}

		mem.sync();
	}

	/** Writes a double array into a {@link NativeBuffer} and flushes shared memory. */
	private void writeBuffer(NativeBuffer mem, int offset, double[] source, int srcOffset, int length) {
		if (mem.getBuffer() instanceof DoubleBuffer buffer) {
			buffer.position(offset);
			buffer.put(source, srcOffset, length);
		} else if (mem.getBuffer() instanceof FloatBuffer buffer) {
			buffer.position(offset);
			for (int i = 0; i < length; i++) {
				buffer.put((float) source[srcOffset + i]);
			}
		} else if (mem.getBuffer() instanceof ShortBuffer) {
			throw new UnsupportedOperationException();
		} else {
			throw new HardwareException("Unsupported precision");
		}

		mem.sync();
	}

	/** Reads a {@link NativeBuffer} into a double array. */
	private void readBuffer(NativeBuffer mem, int sOffset, double[] out, int oOffset, int length) {
		if (mem.getBuffer() instanceof DoubleBuffer buffer) {
			buffer.position(sOffset);
			buffer.get(out, oOffset, length);
		} else if (mem.getBuffer() instanceof FloatBuffer buffer) {
			buffer.position(sOffset);
			for (int i = 0; i < length; i++) {
				out[oOffset + i] = buffer.get();
			}
		} else if (mem.getBuffer() instanceof ShortBuffer) {
			throw new UnsupportedOperationException();
		} else {
			throw new HardwareException("Unsupported precision");
		}
	}

	@Override
	public synchronized void destroy() {
		getAllocated().forEach(this::deallocate);
		memoryUsed = 0;

		super.destroy();
	}

	/**
	 * Registers a custom allocator that can create a {@link NativeBuffer} from a foreign memory type.
	 *
	 * @param <T>       Foreign memory type
	 * @param cls       Class of the foreign memory type
	 * @param allocator Allocator that converts the foreign memory to a native buffer
	 */
	public static <T extends Memory> void registerAdapter(Class<T> cls, NativeBufferAllocator<T> allocator) {
		allocationAdapters.put(cls, allocator);
	}

	/**
	 * Registers a custom writer that can copy data from a foreign memory type into a {@link NativeBuffer}.
	 *
	 * @param <T>    Foreign memory type
	 * @param cls    Class of the foreign memory type
	 * @param writer Writer that copies from the foreign memory into a native buffer
	 */
	public static <T extends Memory> void registerAdapter(Class<T> cls, NativeBufferWriter<T> writer) {
		writeAdapters.put(cls, writer);
	}
}
