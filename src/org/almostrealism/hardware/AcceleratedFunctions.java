/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.hardware;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A wrapper for kernel programs in JOCL.
 */
public class AcceleratedFunctions {
	private Hardware hardware;
	private HardwareOperatorMap base;
	private Map<Class, HardwareOperatorMap> extensions;

	protected synchronized void init(Hardware h, String src) {
		hardware = h;
		base = new HardwareOperatorMap(h, src);
		extensions = new HashMap<>();
	}

	public synchronized HardwareOperatorMap getOperators() { return base; }

	public synchronized HardwareOperatorMap getOperators(Class c) {
		if (!extensions.containsKey(c)) {
			InputStream in = c.getResourceAsStream(c.getSimpleName() + ".cl");
			if (in == null) {
				extensions.put(c, base);
			} else {
				extensions.put(c, new HardwareOperatorMap(hardware, Hardware.getLocalHardware().loadSource(in)));
			}
		}

		return extensions.get(c);
	}

	public synchronized HardwareOperatorMap getOperators(String source) {
		StringBuffer buf = new StringBuffer();
		buf.append(Hardware.getLocalHardware().loadSource());
		buf.append("\n");
		buf.append(source);

		return new HardwareOperatorMap(hardware, buf.toString());
	}
}
