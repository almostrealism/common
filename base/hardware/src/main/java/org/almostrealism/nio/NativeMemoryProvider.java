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
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.mem.DirectMemory;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.mem.NativeRef;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.io.DistributionMetric;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.io.TimingMetric;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Memory provider for CPU-side native memory, backed by either NIO direct buffers or JNI malloc.
 *
 * <p>This provider unifies the two ways the framework allocates host-accessible native memory. Both
 * expose their contents as a {@link ByteBuffer} (through {@link DirectMemory}), so another backend
 * can move data to and from either in bulk without an intermediate array — for example an OpenCL
 * provider reading or writing the buffer directly:</p>
 *
 * <ul>
 *   <li><b>Direct mode (default)</b> allocates a {@link NativeBuffer} via
 *       {@link java.nio.ByteBuffer#allocateDirect} and supports named shared memory. Its content
 *       pointer and shared-memory mappings are produced by native operations this provider compiles
 *       at runtime, so it needs no prebuilt native library.</li>
 *   <li><b>Calloc mode</b> allocates a {@link NativeMemory} via JNI {@link Malloc}; its ByteBuffer
 *       view is produced by a runtime-compiled {@code NewDirectByteBuffer} operation
 *       ({@link NativeBufferView}). It avoids the JVM's direct-memory accounting (and the associated
 *       {@code -XX:MaxDirectMemorySize} limit and {@code System.gc()} pressure).</li>
 * </ul>
 *
 * <p>Both modes work on every platform the native backend supports.</p>
 *
 * <p>The mode is selected per instance and does not change the {@link io.almostrealism.code.MemoryProvider}
 * contract: it only affects how memory is allocated and reclaimed. Which mode the standalone native
 * provider uses by default is resolved by {@link org.almostrealism.hardware.Hardware} from
 * {@code AR_HARDWARE_NATIVE_DIRECT_BUFFERS} and threaded in through the constructor. That setting is
 * distinct from {@code AR_HARDWARE_NIO_MEMORY}, which instead shares a single host provider across
 * backends (see {@link #sharedBridge(Precision, long)}).</p>
 *
 * <h2>Native operations</h2>
 *
 * <p>The runtime-compiled JNI operations both modes rely on ({@link Malloc}, {@link Free},
 * {@link NativeRead}, {@link NativeWrite}, {@link NativeBufferView} for calloc; {@link NativeBufferPointer},
 * {@link SharedMemoryMap}, {@link SharedMemorySync}, {@link SharedMemoryUnmap} for direct buffers) are
 * owned and compiled lazily by this provider. Each op is compiled with {@link #compiler()}, which uses
 * the {@link NativeCompiler} supplied at construction or, when none was supplied (the shared-memory
 * bridge), one it constructs on first use.</p>
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
 * @see HardwareMemoryProvider
 */
public class NativeMemoryProvider extends HardwareMemoryProvider<RAM> {
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

	/** The native compiler supplied at construction; may be null (see {@link #compiler()}). */
	private final NativeCompiler compiler;
	/** The resolved compiler used to build native operations, created on first use if none was supplied. */
	private volatile NativeCompiler resolvedCompiler;

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

	/** Native operation returning the address of a direct buffer (direct mode). */
	private volatile NativeBufferPointer pointerOp;
	/** Native operation mapping named shared memory into a direct buffer (direct mode). */
	private SharedMemoryMap mapOp;
	/** Native operation flushing a shared-memory mapping (direct mode). */
	private SharedMemorySync syncOp;
	/** Native operation unmapping a shared-memory region (direct mode). */
	private SharedMemoryUnmap unmapOp;

	/** Total bytes currently allocated across all active memory in this provider. */
	private long memoryUsed;

	/**
	 * Creates the shared-memory bridge provider — a single host provider shared across backends when
	 * {@code AR_HARDWARE_NIO_MEMORY} is enabled.
	 *
	 * <p>The bridge always allocates {@link NativeBuffer} (direct mode) with named shared memory
	 * ({@code shared = true}), the configuration required for cross-backend sharing; this factory is
	 * the one enforced way to build it, so callers cannot accidentally create a non-shareable bridge.
	 * No compiler is supplied — the native operations direct buffers need are compiled on first use via
	 * {@link #compiler()}.</p>
	 *
	 * @param precision Numeric precision for elements
	 * @param memoryMax Maximum bytes that may be allocated
	 * @return a shared, direct-buffer provider suitable for use as the cross-backend bridge
	 */
	public static NativeMemoryProvider sharedBridge(Precision precision, long memoryMax) {
		return new NativeMemoryProvider(precision, memoryMax, true, null, true);
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

	/** Returns whether this provider uses named shared memory (required of the cross-backend bridge). */
	public boolean isShared() { return shared; }

	/**
	 * Returns the compiler used to build this provider's native operations.
	 *
	 * <p>Uses the {@link NativeCompiler} supplied at construction when present; otherwise (the
	 * shared-memory bridge is created without one) constructs a compiler on first use. The direct-mode
	 * operations perform no numeric work, so the precision of a self-constructed compiler is immaterial.</p>
	 *
	 * @return the resolved native compiler
	 */
	protected NativeCompiler compiler() {
		NativeCompiler c = resolvedCompiler;
		if (c == null) c = initCompiler();
		return c;
	}

	/** Resolves the compiler under lock if it has not been resolved yet. */
	private synchronized NativeCompiler initCompiler() {
		if (resolvedCompiler == null) {
			resolvedCompiler = compiler != null ? compiler :
					NativeCompiler.factory(precision, false).construct();
		}

		return resolvedCompiler;
	}

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
			if (malloc == null) malloc = new Malloc(compiler());

			long bytes = getNumberSize() * (long) size;
			long pointer = malloc.apply(getNumberSize() * size);
			mem = allocated(new NativeMemory(this, pointer, bytes));
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
	 * double-freeing memory owned by the JVM's direct-buffer cleaner. The raw cast is required because
	 * {@link NativeBufferRef} is a {@code NativeRef<NativeBuffer>} while this provider tracks
	 * {@code NativeRef<RAM>}, and the two are unrelated by invariance.</p>
	 */
	@Override
	protected NativeRef<RAM> nativeRef(RAM ram) {
		if (ram instanceof NativeBuffer) {
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
				unmapSharedMemory(bufferRef.getRootBuffer(), bufferRef.getRootBuffer().capacity());
			}

			bufferRef.getDeallocationListeners().forEach(l -> l.accept(null));
		} else {
			if (free == null) free = new Free(compiler());
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

	/**
	 * {@inheritDoc}
	 *
	 * <p>A registered {@link NativeBufferWriter} can read a foreign source (for example an OpenCL
	 * buffer) directly into this destination's host buffer, serving either the calloc or the NIO
	 * backing; otherwise the source is mediated through a {@code double[]}.</p>
	 */
	@Override
	public synchronized void setMem(RAM mem, int offset, Memory source, int srcOffset, int length) {
		if (mem instanceof NativeBuffer buffer && source instanceof NativeBuffer sourceBuffer) {
			copyBuffer(buffer, offset, sourceBuffer, srcOffset, length);
			return;
		}

		if (mem instanceof DirectMemory buffer && writeAdapters.containsKey(source.getClass())) {
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
			if (write == null) write = new NativeWrite(compiler());

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
			if (read == null) read = new NativeRead(compiler());

			long start = System.nanoTime();
			try {
				read.apply((NativeMemory) mem, sOffset, out, oOffset, length);
			} finally {
				ioTime.addEntry("getMem", System.nanoTime() - start);
			}
		}
	}

	/**
	 * Returns the native address backing the given direct buffer, compiling the operation on first use.
	 *
	 * @param b a direct {@link Buffer}
	 * @return the native pointer to the buffer's backing memory
	 */
	long pointerForBuffer(Buffer b) {
		NativeBufferPointer op = pointerOp;
		if (op == null) op = initPointerOp();
		return op.apply(b);
	}

	/** Compiles the direct-buffer pointer operation under lock if it has not been compiled yet. */
	private synchronized NativeBufferPointer initPointerOp() {
		if (pointerOp == null) pointerOp = new NativeBufferPointer(compiler());
		return pointerOp;
	}

	/**
	 * Maps a named shared-memory region into a direct {@link ByteBuffer}, compiling the operation on
	 * first use.
	 *
	 * @param filePath path name for the shared-memory region
	 * @param length   size in bytes to map
	 * @return a direct buffer backed by the shared-memory region
	 */
	synchronized ByteBuffer mapSharedMemory(String filePath, int length) {
		if (mapOp == null) mapOp = new SharedMemoryMap(compiler());
		return mapOp.apply(filePath, length);
	}

	/**
	 * Flushes changes in a shared-memory buffer back to the underlying region, compiling the operation
	 * on first use.
	 *
	 * @param buffer shared-memory buffer to sync
	 * @param length number of bytes to sync
	 */
	synchronized void syncSharedMemory(ByteBuffer buffer, int length) {
		if (syncOp == null) syncOp = new SharedMemorySync(compiler());
		syncOp.apply(buffer, length);
	}

	/**
	 * Unmaps a shared-memory region previously mapped via {@link #mapSharedMemory}, compiling the
	 * operation on first use.
	 *
	 * @param buffer shared-memory buffer to unmap
	 * @param length number of bytes to unmap
	 */
	synchronized void unmapSharedMemory(ByteBuffer buffer, int length) {
		if (unmapOp == null) unmapOp = new SharedMemoryUnmap(compiler());
		unmapOp.apply(buffer, length);
	}

	/**
	 * Returns a direct {@link ByteBuffer} view over a range of calloc memory, compiling the
	 * {@code NewDirectByteBuffer} operation on first use. Backs {@link NativeMemory#asByteBuffer()}.
	 *
	 * @param pointer the native address of the allocation
	 * @param bytes   the length of the range, in bytes
	 * @return a non-owning direct buffer over the range
	 */
	synchronized ByteBuffer viewBuffer(long pointer, long bytes) {
		if (bufferView == null) bufferView = new NativeBufferView(compiler());
		return bufferView.apply(pointer, 0, bytes);
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
