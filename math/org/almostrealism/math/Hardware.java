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

import org.jocl.cl_command_queue;
import org.jocl.cl_context;

/** An interface to OpenCL. */
public final class Hardware {
	private static Hardware local = new Hardware("local");

	private cl_context context;
	private cl_command_queue queue;

	private AcceleratedFunctions functions;

	private Hardware(String name) {
		functions = new AcceleratedFunctions();
	}

	public static Hardware getLocalHardware() { return local; }

	public cl_context getContext() { return context; }

	public cl_command_queue getQueue() { return queue; }

	public AcceleratedFunctions getFunctions() { return functions; }

	private static String loadSource(String name) {
		// TODO
		throw new RuntimeException("loadSource not implemented");
	}
}
