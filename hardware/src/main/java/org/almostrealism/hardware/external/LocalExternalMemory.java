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

package org.almostrealism.hardware.external;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.RAM;
import org.jocl.cl_mem;

import java.io.File;
import java.io.IOException;

public class LocalExternalMemory implements Memory {
	private LocalExternalMemoryProvider provider;
	protected File location;
	protected double data[];
	private int len;

	protected LocalExternalMemory(LocalExternalMemoryProvider provider, File location, int len) {
		this.provider = provider;
		this.location = location;
		this.len = len;
	}

	public void read() {
		if (data != null) return;

		data = new double[len];

		try {
			LocalExternalMemoryProvider.readBinary(location, data);
			location.delete();
		} catch (IOException e) {
			throw new HardwareException("Unable to retrieve external memory", e);
		}
	}

	public void write() {
		if (data == null) return;

		try {
			LocalExternalMemoryProvider.writeBinary(location, this, len);
		} catch (IOException e) {
			throw new HardwareException("Unable to store external memory", e);
		}
	}

	public void restore() {
		this.data = null;
	}

	@Override
	public MemoryProvider getProvider() { return provider; }

	public void destroy() {
		this.data = null;
		this.location.delete();
		this.location = null;
	}
}
