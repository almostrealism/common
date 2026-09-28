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

package org.almostrealism.hardware.cuda;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * JNI bridge to the CUDA Driver API and NVRTC ({@code libARCUDA-linux-<arch>.so}).
 *
 * <p>This is the only class in which CUDA native handles appear as raw {@code long}
 * (or, for devices, {@code int}) values. Every other class holds them through a typed
 * wrapper — {@link CUDevice}, {@link CUContext}, {@link CUStream}, {@link CUEvent},
 * {@link CUModule}, {@link CUFunction} and {@link CUDeviceBuffer}.</p>
 *
 * <h2>Library Loading</h2>
 *
 * <p>The library for the current architecture is extracted from the classpath to a
 * per-process file in the OS temp directory and loaded, following the convention of the
 * Metal bridge, so that concurrent JVMs sharing a temp directory never overwrite a library
 * another process is loading. On a host without the CUDA driver the load fails with an
 * {@link UnsatisfiedLinkError}, which hardware initialization treats like any other
 * unavailable backend.</p>
 *
 * <h2>Contexts and Errors</h2>
 *
 * <p>Every entry point that operates within a CUDA context receives that context
 * explicitly and makes it current on the calling thread, because a CUDA context is
 * current per thread and the framework calls in from many threads. Every failing driver
 * or NVRTC call throws {@link org.almostrealism.hardware.HardwareException} carrying the
 * CUDA error name and description (and the NVRTC log for compile failures).</p>
 *
 * <h2>Kernel Parameters</h2>
 *
 * <p>{@link #launchKernel} packs parameters in the order the generated signature declares
 * them: every argument pointer, then every argument offset, then every argument size,
 * then {@code global_count} and {@code global_offset}.</p>
 */
public final class CU {
	/** {@code CU_DEVICE_ATTRIBUTE_MAX_THREADS_PER_BLOCK}. */
	public static final int DEVICE_ATTRIBUTE_MAX_THREADS_PER_BLOCK = 1;
	/** {@code CU_DEVICE_ATTRIBUTE_MAX_GRID_DIM_X}. */
	public static final int DEVICE_ATTRIBUTE_MAX_GRID_DIM_X = 5;
	/** {@code CU_DEVICE_ATTRIBUTE_WARP_SIZE}. */
	public static final int DEVICE_ATTRIBUTE_WARP_SIZE = 10;
	/** {@code CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT}. */
	public static final int DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT = 16;
	/** {@code CU_DEVICE_ATTRIBUTE_INTEGRATED}. */
	public static final int DEVICE_ATTRIBUTE_INTEGRATED = 18;
	/** {@code CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR}. */
	public static final int DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR = 75;
	/** {@code CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR}. */
	public static final int DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR = 76;
	/** {@code CU_DEVICE_ATTRIBUTE_MANAGED_MEMORY}. */
	public static final int DEVICE_ATTRIBUTE_MANAGED_MEMORY = 83;
	/** {@code CU_DEVICE_ATTRIBUTE_PAGEABLE_MEMORY_ACCESS}. */
	public static final int DEVICE_ATTRIBUTE_PAGEABLE_MEMORY_ACCESS = 88;
	/** {@code CU_DEVICE_ATTRIBUTE_CONCURRENT_MANAGED_ACCESS}. */
	public static final int DEVICE_ATTRIBUTE_CONCURRENT_MANAGED_ACCESS = 89;

	/** {@code CU_FUNC_ATTRIBUTE_MAX_THREADS_PER_BLOCK}. */
	public static final int FUNC_ATTRIBUTE_MAX_THREADS_PER_BLOCK = 0;

	static {
		String resource = "libARCUDA-linux-" + System.getProperty("os.arch") + ".so";
		InputStream is = CU.class.getClassLoader().getResourceAsStream(resource);
		if (is == null) {
			throw new UnsatisfiedLinkError("No CUDA bridge library " + resource + " on the classpath");
		}

		File tempDir = new File(System.getProperty("java.io.tmpdir"));
		tempDir.mkdir();

		File tempLibFile = new File(tempDir, "libARCUDA-" + ProcessHandle.current().pid() + ".so");
		try (InputStream in = is) {
			Files.copy(in, tempLibFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			tempLibFile.deleteOnExit();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		System.load(tempLibFile.getAbsolutePath());
		init();
	}

	/** Not instantiable; all bindings are static. */
	private CU() { }

	/** Initializes the driver API ({@code cuInit}). Called once when the library loads. */
	private static native void init();

	/** Returns the driver's CUDA version, encoded as {@code 1000 * major + 10 * minor}. */
	public static native int driverVersion();

	/** Returns the NVRTC version, encoded as {@code 1000 * major + 10 * minor}. */
	public static native int nvrtcVersion();

	/** Returns the number of CUDA devices visible to this process. */
	public static native int deviceCount();

	/** Returns the device handle for the given ordinal. */
	public static native int device(int ordinal);

	/** Returns the name of a device. */
	public static native String deviceName(int device);

	/** Returns the value of a {@code CU_DEVICE_ATTRIBUTE_*} attribute. */
	public static native int deviceAttribute(int device, int attribute);

	/** Returns the total memory of a device in bytes. */
	public static native long totalMemory(int device);

	/** Retains the primary context of a device and returns it. */
	public static native long primaryContextRetain(int device);

	/** Releases one retain of the primary context of a device. */
	public static native void primaryContextRelease(int device);

	/** Blocks until all work in a context has completed. */
	public static native void synchronize(long context);

	/**
	 * Compiles CUDA C++ source with NVRTC.
	 *
	 * @param source  the source to compile
	 * @param name    the program name used in diagnostics
	 * @param options NVRTC options, such as {@code --gpu-architecture=sm_121}
	 * @param cubin   true to return a CUBIN (requires a real {@code sm_} architecture),
	 *                false to return PTX
	 * @return the compiled image
	 */
	public static native byte[] compile(String source, String name, String[] options, boolean cubin);

	/** Loads a compiled image (CUBIN or PTX) as a module in a context. */
	public static native long moduleLoadData(long context, byte[] image);

	/** Unloads a module. */
	public static native void moduleUnload(long context, long module);

	/** Returns the kernel with the given (unmangled) name from a module. */
	public static native long moduleGetFunction(long context, long module, String name);

	/** Returns the value of a {@code CU_FUNC_ATTRIBUTE_*} attribute of a kernel. */
	public static native int functionAttribute(long context, long function, int attribute);

	/** Allocates zero-filled device memory. */
	public static native long memAlloc(long context, long bytes);

	/** Allocates zero-filled managed memory, addressable from both host and device. */
	public static native long memAllocManaged(long context, long bytes);

	/** Frees device or managed memory. */
	public static native void memFree(long context, long pointer);

	/** Copies from a direct buffer on the host to device memory. Offsets are in bytes. */
	public static native void memcpyHtoD(long context, long destination, long destinationOffset,
										 Buffer source, long sourceOffset, long bytes);

	/** Copies from device memory to a direct buffer on the host. Offsets are in bytes. */
	public static native void memcpyDtoH(long context, Buffer destination, long destinationOffset,
										 long source, long sourceOffset, long bytes);

	/** Copies between device allocations, synchronously. Addresses include any offset. */
	public static native void memcpyDtoD(long context, long destination, long source, long bytes);

	/** Enqueues a copy between device allocations on a stream. Addresses include any offset. */
	public static native void memcpyDtoDAsync(long context, long destination, long source,
											  long bytes, long stream);

	/** Creates a non-blocking stream. */
	public static native long streamCreate(long context);

	/** Destroys a stream. */
	public static native void streamDestroy(long context, long stream);

	/** Blocks until all work on a stream has completed. */
	public static native void streamSynchronize(long context, long stream);

	/** Makes future work on a stream wait for an event. */
	public static native void streamWaitEvent(long context, long stream, long event);

	/** Creates an event with timing disabled. */
	public static native long eventCreate(long context);

	/** Records an event on a stream. */
	public static native void eventRecord(long context, long event, long stream);

	/** Returns true if all work captured by an event has completed. */
	public static native boolean eventQuery(long context, long event);

	/** Blocks until all work captured by an event has completed. */
	public static native void eventSynchronize(long context, long event);

	/** Destroys an event. */
	public static native void eventDestroy(long context, long event);

	/**
	 * Launches a kernel with a one-dimensional grid.
	 *
	 * @param context      the context owning the kernel
	 * @param function     the kernel
	 * @param gridX        number of blocks
	 * @param blockX       threads per block
	 * @param pointers     one device address per argument
	 * @param offsets      one element offset per argument
	 * @param sizes        one element count per argument
	 * @param globalCount  the number of work items (threads beyond this return immediately)
	 * @param globalOffset the index of the first work item
	 * @param stream       the stream to launch on
	 */
	public static native void launchKernel(long context, long function, int gridX, int blockX,
										   long[] pointers, int[] offsets, int[] sizes,
										   long globalCount, long globalOffset, long stream);
}
