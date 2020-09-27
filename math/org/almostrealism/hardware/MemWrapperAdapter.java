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

import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.cl_mem;

public abstract class MemWrapperAdapter implements MemWrapper {
	private cl_mem mem;

	private MemWrapper delegateMem;
	private int delegateMemOffset;

	protected void init() {
		if (delegateMem == null) {
			PooledMem pool = getDefaultDelegate();

			if (pool == null) {
				mem = Hardware.getLocalHardware().allocate(getMemLength());
			} else {
				setDelegate(pool, pool.reserveOffset(this));
				setMem(new double[getMemLength()]);
			}
		}
	}

	@Override
	public cl_mem getMem() { return delegateMem == null ? mem : delegateMem.getMem(); }

	@Override
	public int getOffset() { return delegateMem == null ? 0 : delegateMemOffset; }

	@Override
	public MemWrapper getDelegate() { return delegateMem; }

	@Override
	public int getDelegateOffset() { return delegateMemOffset; }

	@Override
	public void destroy() {
		if (mem == null) return;
		Hardware.getLocalHardware().deallocate(getMemLength(), mem);
		mem = null;
	}

	@Override
	public void setDelegate(MemWrapper m, int offset) {
		this.delegateMem = m;
		this.delegateMemOffset = offset;
	}

	public PooledMem getDefaultDelegate() { return null; }

	@Override
	public void finalize() {
		destroy();
	}
}
