/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.RAM;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.jni.NativeComputeContext;
import org.jocl.CL;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_event;
import org.jocl.cl_platform_id;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

public class CLDataContext implements DataContext {
	private final Hardware hardware;
	private final String name;
	private final long memoryMax;
	private final CLMemoryProvider.Location location;

	private cl_platform_id platform;
	private cl_device_id mainDevice;
	private cl_device_id kernelDevice;
	private cl_context ctx;

	private MemoryProvider<RAM> ram;

	private ThreadLocal<ComputeContext> computeContext;

	public CLDataContext(Hardware hardware, String name, long memoryMax, CLMemoryProvider.Location location) {
		this.hardware = hardware;
		this.name = name;
		this.memoryMax = memoryMax;
		this.location = location;
		this.computeContext = new ThreadLocal<>();
	}

	public void init(cl_platform_id platform, cl_device_id mainDevice, cl_device_id kernelDevice) {
		if (ctx != null) return;

		this.platform = platform;
		this.mainDevice = mainDevice;
		this.kernelDevice = kernelDevice;

		cl_context_properties contextProperties = new cl_context_properties();
		contextProperties.addProperty(CL.CL_CONTEXT_PLATFORM, platform);

		if (kernelDevice == null) {
			ctx = CL.clCreateContext(contextProperties, 1, new cl_device_id[]{mainDevice},
					null, null, null);
		} else {
			ctx = CL.clCreateContext(contextProperties, 2, new cl_device_id[]{mainDevice, kernelDevice},
					null, null, null);
		}

		if (Hardware.enableVerbose) System.out.println("Hardware[" + name + "]: OpenCL context initialized");

		ram = new CLMemoryProvider(this, hardware.getNumberSize(), memoryMax, location);
	}

	private ComputeContext createContext(ComputeRequirement... expectations) {
		Optional<ComputeRequirement> cReq = Stream.of(expectations).filter(ComputeRequirement.C::equals).findAny();
		Optional<ComputeRequirement> pReq = Stream.of(expectations).filter(ComputeRequirement.PROFILING::equals).findAny();

		ComputeContext cc;

		if (cReq.isPresent()) {
			cc = new CLNativeComputeContext(hardware);
		} else {
			cc = new CLComputeContext(hardware, ctx);
			((CLComputeContext) cc).init(mainDevice, kernelDevice, pReq.isPresent());
		}

		return cc;
	}

	public String getName() { return name; }

	public cl_context getClContext() { return ctx; }

	public MemoryProvider<RAM> getMemoryProvider() { return ram; }

	public ComputeContext getComputeContext() {
		if (computeContext.get() == null) {
			if (Hardware.enableVerbose) System.out.println("INFO: No explicit ComputeContext for " + Thread.currentThread().getName());
			computeContext.set(createContext());
		}

		return computeContext.get();
	}

	public CLComputeContext getClComputeContext() {
		return (CLComputeContext) getComputeContext();
	}

	public <T> T computeContext(Callable<T> exec, ComputeRequirement... expectations) {
		ComputeContext current = computeContext.get();
		ComputeContext next = createContext(expectations);

		String ccName = next.toString();
		if (ccName.contains(".")) {
			ccName = ccName.substring(ccName.lastIndexOf('.') + 1);
		}

		try {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Start " + ccName);
			computeContext.set(next);
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: End " + ccName);
			next.destroy();
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Destroyed " + ccName);
			computeContext.set(current);
		}
	}

	@Override
	public void destroy() {
		// TODO  Destroy any other compute contexts
		if (computeContext.get() != null) {
			computeContext.get().destroy();
			computeContext.remove();
		}

		CL.clReleaseContext(ctx);
		ctx = null;
	}
}
