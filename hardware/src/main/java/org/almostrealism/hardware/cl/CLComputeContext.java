/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeEncoder;
import org.almostrealism.c.OpenCLPrintWriter;
import org.almostrealism.hardware.AbstractComputeContext;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.profile.ProfileData;
import org.almostrealism.hardware.profile.RunData;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_device_id;
import org.jocl.cl_event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class CLComputeContext extends AbstractComputeContext {
	public static boolean enableFastQueue = true;

	private static final String fp64 = "#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n";

	private boolean enableFp64;
	private cl_context ctx;
	private cl_command_queue queue;
	private cl_command_queue fastQueue;
	private cl_command_queue kernelQueue;
	private boolean profiling;
	private Map<String, ProfileData> profiles;

	private List<HardwareOperatorMap> instructionSets;

	public CLComputeContext(Hardware hardware, cl_context ctx) {
		super(hardware, true, false);
		this.enableFp64 = hardware.isDoublePrecision();
		this.ctx = ctx;
		this.instructionSets = new ArrayList<>();
		this.profiles = new HashMap<>();
	}

	protected void init(cl_device_id mainDevice, cl_device_id kernelDevice, boolean profiling) {
		if (queue != null) return;

		this.profiling = profiling;

		if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: OpenCL context initialized");

		queue = CL.clCreateCommandQueue(ctx, mainDevice, profiling ? CL.CL_QUEUE_PROFILING_ENABLE : 0, null);
		if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: OpenCL command queue initialized");

		if (enableFastQueue) {
			fastQueue = CL.clCreateCommandQueue(ctx, mainDevice, profiling ? CL.CL_QUEUE_PROFILING_ENABLE : 0, null);
			if (Hardware.enableVerbose)
				System.out.println("Hardware[" + getName() + "]: OpenCL fast command queue initialized");
		}

		if (kernelDevice != null) {
			kernelQueue = CL.clCreateCommandQueue(ctx, kernelDevice, profiling ? CL.CL_QUEUE_PROFILING_ENABLE : 0, null);
			if (Hardware.enableVerbose)
				System.out.println("Hardware[" + getName() + "]: OpenCL kernel command queue initialized");
		}
	}

	@Override
	public InstructionSet deliver(Scope scope) {
		StringBuffer buf = new StringBuffer();
		if (enableFp64) buf.append(fp64);

		ScopeEncoder enc = new ScopeEncoder(OpenCLPrintWriter::new, Accessibility.EXTERNAL);
		buf.append(enc.apply(scope));

		HardwareOperatorMap instSet = new HardwareOperatorMap(this, buf.toString(), profileFor(scope.getName()));
		instructionSets.add(instSet);
		return instSet;
	}

	@Override
	public boolean isKernelSupported() { return true; }

	protected cl_context getCLContext() {
		return ctx;
	}

	public cl_command_queue getClQueue() { return queue; }

	public cl_command_queue getClQueue(boolean kernel) { return kernel ? getKernelClQueue() : getClQueue(); }

	public cl_command_queue getFastClQueue() { return fastQueue == null ? getClQueue() : fastQueue; }

	public cl_command_queue getKernelClQueue() { return kernelQueue == null ? getClQueue() : kernelQueue; }

	protected Consumer<RunData> profileFor(String name) {
		if (!profiles.containsKey(name)) profiles.put(name, new ProfileData());
		return profiles.get(name)::addRun;
	}

	protected void processEvent(cl_event event) { processEvent(event, null); }

	protected void processEvent(cl_event event, Consumer<RunData> profile) {
		CL.clWaitForEvents(1, new cl_event[] { event });

		if (profiling && profile != null) {
			long startTime[] = { 0 };
			long endTime[] = { 0 };
			CL.clGetEventProfilingInfo(event, CL.CL_PROFILING_COMMAND_START, Sizeof.cl_ulong, Pointer.to(startTime), null);
			CL.clGetEventProfilingInfo(event, CL.CL_PROFILING_COMMAND_END, Sizeof.cl_ulong, Pointer.to(endTime), null);
			profile.accept(new RunData(endTime[0] - startTime[0]));
		}

		CL.clReleaseEvent(event);
	}

	public void logProfiles() {
		if (profiles.size() <= 0) {
			System.out.println("No profiling results");
			return;
		}

		List<String> names = new ArrayList<>();
		names.addAll(profiles.keySet());

		Comparator<String> totalDuration = (a, b) -> (int) (profiles.get(b).getTotalRuntimeNanos() - profiles.get(a).getTotalRuntimeNanos());
		Comparator<String> totalCount = (a, b) -> (int) (profiles.get(b).getTotalRuns() - profiles.get(a).getTotalRuns());

		int include = 4;

		System.out.println("Profiler Results:");

		names.sort(totalDuration);

		System.out.println("\tTop Total Durations:");
		for (int i = 0; i < Math.min(include, names.size()); i++) {
			System.out.println("\t\t-" + names.get(i) + ": " + profiles.get(names.get(i)).getSummaryString());
		}

		System.out.println("\tBottom Total Durations:");
		for (int i = 0; i < Math.min(include, names.size()); i++) {
			System.out.println("\t\t-" + names.get(names.size() - i - 1) + ": " + profiles.get(names.get(names.size() - i - 1)).getSummaryString());
		}

		names.sort(totalCount.thenComparing(totalDuration));

		System.out.println("\tTop Execution Count:");
		for (int i = 0; i < Math.min(include, names.size()); i++) {
			System.out.println("\t\t-" + names.get(i) + ": " + profiles.get(names.get(i)).getSummaryString());
		}
	}

	@Override
	public void destroy() {
		if (profiling) logProfiles();
		this.instructionSets.forEach(InstructionSet::destroy);
		if (queue != null) CL.clReleaseCommandQueue(queue);
		if (fastQueue != null) CL.clReleaseCommandQueue(fastQueue);
		if (kernelQueue != null) CL.clReleaseCommandQueue(kernelQueue);
		queue = null;
		fastQueue = null;
		kernelQueue = null;
	}
}
