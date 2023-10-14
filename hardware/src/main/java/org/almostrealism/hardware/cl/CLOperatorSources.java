/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.code.OperationMetadata;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.Issues;
import org.almostrealism.hardware.Precision;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * A wrapper for kernel programs in JOCL.
 */
@Deprecated
public class CLOperatorSources {
	private CLComputeContext context;
	private CLOperatorMap base;
	private Map<Class, CLOperatorMap> extensions;

	protected synchronized void init(CLComputeContext ctx, String name) {
		context = ctx;

		String src = loadSource(name);
		base = new CLOperatorMap(context,
				new OperationMetadata("AcceleratedFunctions", "Built in functions"), src, null);
		extensions = new HashMap<>();
	}

	public synchronized CLOperatorMap getOperators() { return base; }

	public synchronized CLOperatorMap getOperators(Class c) {
		if (!extensions.containsKey(c)) {
			InputStream in = c.getResourceAsStream(c.getSimpleName() + ".cl");
			if (in == null) {
				extensions.put(c, base);
			} else {
				extensions.put(c, new CLOperatorMap(context,
						new OperationMetadata(c.getSimpleName(), "Custom CL Code"),
						loadSource(in), null));
			}
		}

		return extensions.get(c);
	}


	protected String loadSource() {
		return loadSource(context.getHardware().getPrecision() == Precision.FP64 ? "local64" : "local32");
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

		StringBuilder buf = new StringBuilder();

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
