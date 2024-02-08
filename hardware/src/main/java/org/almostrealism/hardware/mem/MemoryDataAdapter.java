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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.MemoryData;

import java.util.HashMap;
import java.util.Map;

public abstract class MemoryDataAdapter implements MemoryData {
	public static boolean enableMemVersions = true;

	private Memory mem;
	private Map<MemoryProvider, Memory> memVersions;

	private MemoryData delegateMem;
	private int delegateMemOffset;

	protected void init() {
		if (getDelegate() == null) {
			Heap heap = getDefaultDelegate();

			if (heap == null) {
				mem = Hardware.getLocalHardware().getMemoryProvider(getMemLength()).allocate(getMemLength());
			} else {
				Bytes data = heap.allocate(getMemLength());
				setDelegate(data.getDelegate(), data.getDelegateOffset());
				setMem(new double[getMemLength()]);
			}
		}
	}

	protected void init(Memory mem) {
		this.mem = mem;
	}

	@Override
	public Memory getMem() { return getDelegate() == null ? mem : getDelegate().getMem(); }

	@Override
	public MemoryData getDelegate() { return delegateMem; }

	@Override
	public int getDelegateOffset() { return delegateMemOffset; }

	@Override
	public void reallocate(MemoryProvider<?> provider) {
		if (getOffset() != 0) {
			throw new HardwareException("Cannot reallocate memory with non-zero offset");
		} if (memVersions == null || !memVersions.containsKey(provider)) {
			MemoryData.super.reallocate(provider);
		} else {
			Memory mem = memVersions.get(provider);
			mem.getProvider().setMem(mem, 0, this.mem, 0, getMemLength());
			reassign(mem);
		}
	}

	@Override
	public void reassign(Memory mem) {
		if (delegateMem != null || mem == null) {
			throw new HardwareException("Only root memory can be reassigned");
		}

		if (enableMemVersions && memVersions == null)
			memVersions = new HashMap<>();

		if (memVersions == null) {
			this.mem.getProvider().deallocate(getMemLength(), this.mem);
		} else {
			memVersions.put(this.mem.getProvider(), this.mem);
		}

		this.mem = mem;
	}

	@Override
	public void destroy() {
		if (mem == null) return;
		if (delegateMem != null) {
			System.out.println("WARN: MemoryData has a delegate, but also directly reserved memory");
		}

		mem.getProvider().deallocate(getMemLength(), mem);
		mem = null;
	}

	@Override
	public void setDelegate(MemoryData m, int offset) {
		if (m != null && (offset + getMemLength()) > m.getMemLength()) {
			throw new HardwareException("Delegate offset is out of bounds");
		}

		this.delegateMem = m;
		this.delegateMemOffset = offset;
	}

	public Heap getDefaultDelegate() { return null; }

	@Override
	public void finalize() {
		destroy();
	}
}
