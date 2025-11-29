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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.Precision;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.Console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Loader for manually-authored OpenCL kernel source files.
 *
 * <p>Loads .cl files from resources and compiles them to {@link CLOperatorMap}.
 * This approach is deprecated in favor of generating OpenCL code from {@link io.almostrealism.scope.Scope}.</p>
 *
 * @deprecated Use {@link CLComputeContext#deliver(io.almostrealism.scope.Scope)} for code generation
 * @see CLComputeContext
 */
@Deprecated
public class CLOperatorSources {
	/** The compute context for OpenCL operations. */
	private CLComputeContext context;

	/** The base operator map loaded from built-in .cl files. */
	private CLOperatorMap base;

	/** Cache of operator maps for class-specific .cl resource files. */
	private Map<Class, CLOperatorMap> extensions;

	/**
	 * Initializes the operator sources with the given compute context.
	 * Loads the base kernel source and prepares the extensions cache.
	 *
	 * @param ctx  the compute context for OpenCL operations
	 */
	protected synchronized void init(CLComputeContext ctx) {
		context = ctx;

		String src = loadSource();
		base = new CLOperatorMap(context,
				new OperationMetadata("AcceleratedFunctions", "Built in functions"), src, null);
		extensions = new HashMap<>();
	}

	/**
	 * Returns the operator map for the given class.
	 * Loads class-specific .cl resources if available, otherwise returns the base operator map.
	 *
	 * @param c  the class to get operators for (looks for {@code ClassName.cl} resource)
	 * @return the operator map containing kernels for the class, or the base map if no class-specific resource exists
	 */
	public synchronized CLOperatorMap getOperators(Class c) {
		if (!extensions.containsKey(c)) {
			InputStream in = c.getResourceAsStream(c.getSimpleName() + ".cl");
			if (in == null) {
				extensions.put(c, base);
			} else {
				boolean replaceDouble = context.getDataContext().getPrecision() != Precision.FP64;
				extensions.put(c, new CLOperatorMap(context,
						new OperationMetadata(c.getSimpleName(), "Custom CL Code"),
						loadSource(in, true, replaceDouble), null));
			}
		}

		return extensions.get(c);
	}

	/**
	 * Loads the built-in kernel source for the current precision setting.
	 *
	 * @return the kernel source code (local32.cl for FP32, local64.cl for FP64)
	 */
	private String loadSource() {
		String name = context.getDataContext().getPrecision() == Precision.FP64 ? "local64" : "local32";
		return loadSource(Hardware.class.getClassLoader().getResourceAsStream(name + ".cl"), false, false);
	}

	/**
	 * Loads kernel source code from an input stream.
	 *
	 * @param is             the input stream containing kernel source
	 * @param includeLocal   if true, prepends the built-in local source
	 * @param replaceDouble  if true, replaces "double" with "float" for FP32 precision
	 * @return the loaded and optionally transformed kernel source code
	 * @throws IllegalArgumentException if the input stream is null
	 */
	protected String loadSource(InputStream is, boolean includeLocal, boolean replaceDouble) {
		if (is == null) {
			throw new IllegalArgumentException("InputStream is null");
		}

		StringBuilder buf = new StringBuilder();

		if (includeLocal) {
			buf.append(loadSource());
			buf.append("\n");
		}

		try (BufferedReader in =
					 new BufferedReader(new InputStreamReader(is))) {
			String line;

			while ((line = in.readLine()) != null) {
				if (replaceDouble) {
					line = line.replaceAll("double", "float");
				}

				buf.append(line); buf.append("\n");
			}
		} catch (IOException e) {
			Console.root().warn("Unable to load kernel program source", e);
		}

		return buf.toString();
	}
}
