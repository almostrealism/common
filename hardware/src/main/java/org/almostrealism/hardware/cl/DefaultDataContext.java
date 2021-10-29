package org.almostrealism.hardware.cl;

import io.almostrealism.code.DataContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.RAM;
import org.jocl.CL;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_platform_id;

import java.util.concurrent.Callable;

public class DefaultDataContext implements DataContext {
	private final Hardware hardware;
	private final String name;
	private final boolean isDoublePrecision;
	private final long memoryMax;
	private final CLMemoryProvider.Location location;

	private cl_context ctx;
	private cl_command_queue queue;
	private MemoryProvider<RAM> ram;

	private ThreadLocal<DefaultComputeContext> computeContext;

	public DefaultDataContext(Hardware hardware, String name, boolean isDoublePrecision, long memoryMax, CLMemoryProvider.Location location) {
		this.hardware = hardware;
		this.name = name;
		this.isDoublePrecision = isDoublePrecision;
		this.memoryMax = memoryMax;
		this.location = location;
		this.computeContext = new ThreadLocal<>();
	}

	public void init(cl_platform_id platform, cl_device_id device) {
		if (ctx != null) return;

		cl_context_properties contextProperties = new cl_context_properties();
		contextProperties.addProperty(CL.CL_CONTEXT_PLATFORM, platform);

		ctx = CL.clCreateContext(contextProperties, 1, new cl_device_id[]{device},
				null, null, null);
		if (Hardware.enableVerbose) System.out.println("Hardware[" + name + "]: OpenCL context initialized");

		queue = CL.clCreateCommandQueue(ctx, device, 0, null);
		if (Hardware.enableVerbose) System.out.println("Hardware[" + name + "]: OpenCL command queue initialized");

		ram = new CLMemoryProvider(this, hardware.getNumberSize(), memoryMax, location);
	}

	public String getName() { return name; }

	public cl_context getClContext() { return ctx; }

	public cl_command_queue getClQueue() { return queue; }

	public MemoryProvider<RAM> getMemoryProvider() { return ram; }

	public DefaultComputeContext getComputeContext() {
		if (computeContext.get() == null) {
			System.out.println("INFO: No explicit ComputeContext for " + Thread.currentThread().getName());
			computeContext.set(new DefaultComputeContext(isDoublePrecision, ctx));
		}

		return computeContext.get();
	}

	protected void setComputeContext(DefaultComputeContext ctx) {
		if (this.computeContext.get() != null) {
			this.computeContext.get().destroy();
		}

		this.computeContext.set(ctx);
	}

	public <T> T computeContext(Callable<T> exec) {
		DefaultComputeContext current = computeContext.get();
		DefaultComputeContext next = new DefaultComputeContext(isDoublePrecision, ctx);
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
		// TODO  Destroy all compute contexts

		CL.clReleaseCommandQueue(queue);
		queue = null;

		CL.clReleaseContext(ctx);
		ctx = null;
	}
}
