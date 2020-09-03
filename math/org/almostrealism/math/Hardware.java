/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.math;

import org.almostrealism.log.Issues;
import org.jocl.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/** An interface to OpenCL. */
public final class Hardware {
	private static Hardware local;

	static {
		boolean gpu = "gpu".equalsIgnoreCase(System.getenv("AR_HARDWARE_PLATFORM")) ||
				"gpu".equalsIgnoreCase(System.getProperty("AR_HARDWARE_PLATFORM"));
		local = new Hardware(false, false);
	}

	private final boolean enableGpu;
	private final boolean enableDoublePrecision;

	private cl_context context;
	private cl_command_queue queue;

	private AcceleratedFunctions functions;
	
	private Hardware(boolean enableGpu) {
		this(enableGpu, !enableGpu);
	}

	private Hardware(boolean enableGpu, boolean enableDoublePrecision) {
		this(enableDoublePrecision ? "local64" : "local32", enableGpu, enableDoublePrecision);
	}

	private Hardware(String name, boolean enableGpu) {
		this(name, enableGpu, !enableGpu);
	}

	private Hardware(String name, boolean enableGpu, boolean enableDoublePrecision) {
		this.enableGpu = enableGpu;
		this.enableDoublePrecision = enableDoublePrecision;

		final int platformIndex = 0;
		final int deviceIndex = 0;
		final long deviceType = enableGpu ? CL.CL_DEVICE_TYPE_GPU : CL.CL_DEVICE_TYPE_CPU;

		CL.setExceptionsEnabled(true);

		if (enableGpu) {
			System.out.println("Initializing Hardware (GPU Enabled)...");
		} else {
			System.out.println("Initializing Hardware...");
		}

		int numPlatformsArray[] = new int[1];
		CL.clGetPlatformIDs(0, null, numPlatformsArray);
		int numPlatforms = numPlatformsArray[0];

		System.out.println("Hardware[" + name + "]: " + numPlatforms + " platforms available");

		cl_platform_id platforms[] = new cl_platform_id[numPlatforms];
		CL.clGetPlatformIDs(platforms.length, platforms, null);
		cl_platform_id platform = platforms[platformIndex];

		System.out.println("Hardware[" + name + "]: Using platform " + platformIndex + " -- " + platform);

		cl_context_properties contextProperties = new cl_context_properties();
		contextProperties.addProperty(CL.CL_CONTEXT_PLATFORM, platform);

		int numDevicesArray[] = new int[1];
		CL.clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
		int numDevices = numDevicesArray[0];

		System.out.println("Hardware[" + name + "]: " + numDevices + " " + deviceName(deviceType) + "(s) available");

		cl_device_id devices[] = new cl_device_id[numDevices];
		CL.clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
		cl_device_id device = devices[deviceIndex];

		System.out.println("Hardware[" + name + "]: Using " + deviceName(deviceType) + " " + deviceIndex);

		context = CL.clCreateContext(contextProperties, 1, new cl_device_id[] { device },
								null, null, null);
		System.out.println("Hardware[" + name + "]: OpenCL context initialized");

		queue = CL.clCreateCommandQueue(context, device, 0, null);
		System.out.println("Hardware[" + name + "]: OpenCL command queue initialized");

		System.out.println("Hardware[" + name + "]: Loading accelerated functions");
		functions = new AcceleratedFunctions();
		functions.init(this, loadSource(name));
		System.out.println("Hardware[" + name + "]: Accelerated functions loaded for " + name);
	}

	public static Hardware getLocalHardware() { return local; }

	public boolean isGPU() { return enableGpu; }

	public boolean isDoublePrecision() { return enableDoublePrecision; }

	public int getNumberSize() { return isDoublePrecision() ? Sizeof.cl_double : Sizeof.cl_float; }

	public String stringForDouble(double d) {
		if (isGPU()) {
			Float f = (float) d;
			if (f.isInfinite()) {
				return String.valueOf(f > 0 ? Float.MAX_VALUE : Float.MIN_VALUE);
			} else if (f.isNaN()) {
				return "0.0";
			}

			return String.valueOf((float) d);
		} else {
			Double v = d;
			if (v.isInfinite()) {
				return String.valueOf(v > 0 ? Double.MAX_VALUE : Double.MIN_VALUE);
			} else if (v.isNaN()) {
				return "0.0";
			}

			return String.valueOf(d);
		}
	}

	public cl_context getContext() { return context; }

	public cl_command_queue getQueue() { return queue; }

	public AcceleratedFunctions getFunctions() { return functions; }

	private static String deviceName(long type) {
		if (type == CL.CL_DEVICE_TYPE_CPU) {
			return "CPU";
		} else if (type == CL.CL_DEVICE_TYPE_GPU) {
			return "GPU";
		} else {
			throw new IllegalArgumentException("Unknown device type " + type);
		}
	}

	protected String loadSource() {
		return loadSource(enableDoublePrecision ? "local64" : "local32");
	}

	protected String loadSource(String name) {
		return loadSource(Hardware.class.getClassLoader().getResourceAsStream(name + ".cl"), false);
	}

	protected String loadSource(InputStream is) {
		return loadSource(is, true);
	}

	protected String loadSource(InputStream is, boolean includeLocal) {
		if (is == null) {
			throw new IllegalArgumentException("InputStream is null");
		}

		StringBuffer buf = new StringBuffer();

		if (includeLocal) {
			buf.append(loadSource());
			buf.append("\n");
		}

		try (BufferedReader in =
					 new BufferedReader(new InputStreamReader(is))) {
			String line;

			while ((line = in.readLine()) != null) {
				buf.append(line); buf.append("\n");
			}
		} catch (IOException e) {
			Issues.warn(null, "Unable to load kernel program source", e);
		}

		return buf.toString();
	}
}
