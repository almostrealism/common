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
import java.io.InputStreamReader;

/** An interface to OpenCL. */
public final class Hardware {
	private static Hardware local = new Hardware("local");

	private cl_context context;
	private cl_command_queue queue;

	private AcceleratedFunctions functions;

	private Hardware(String name) {
		final int platformIndex = 0;
		final long deviceType = CL.CL_DEVICE_TYPE_ALL;
		final int deviceIndex = 0;

		CL.setExceptionsEnabled(true);

		int numPlatformsArray[] = new int[1];
		CL.clGetPlatformIDs(0, null, numPlatformsArray);
		int numPlatforms = numPlatformsArray[0];

		cl_platform_id platforms[] = new cl_platform_id[numPlatforms];
		CL.clGetPlatformIDs(platforms.length, platforms, null);
		cl_platform_id platform = platforms[platformIndex];

		cl_context_properties contextProperties = new cl_context_properties();
		contextProperties.addProperty(CL.CL_CONTEXT_PLATFORM, platform);

		int numDevicesArray[] = new int[1];
		CL.clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
		int numDevices = numDevicesArray[0];

		cl_device_id devices[] = new cl_device_id[numDevices];
		CL.clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
		cl_device_id device = devices[deviceIndex];

		context = CL.clCreateContext(contextProperties, 1, new cl_device_id[] { device }, null, null, null);
		queue = CL.clCreateCommandQueue(context, device, 0, null);

		functions = new AcceleratedFunctions();
		functions.init(this, loadSource(name));
	}

	public static Hardware getLocalHardware() { return local; }

	public cl_context getContext() { return context; }

	public cl_command_queue getQueue() { return queue; }

	public AcceleratedFunctions getFunctions() { return functions; }

	private static String loadSource(String name) {
		StringBuffer buf = new StringBuffer();

		try (BufferedReader in =
					 new BufferedReader(new InputStreamReader(
					 		Hardware.class.getClassLoader().getResourceAsStream(name + ".cl")))) {
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
