package org.almostrealism.hardware.mem;

import io.almostrealism.code.Memory;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

public abstract class MemoryReference<T extends Memory> extends WeakReference<T> {
	private StackTraceElement[] allocationStackTrace;

	public MemoryReference(T referent, ReferenceQueue<? super T> q) {
		super(referent, q);
	}

	public StackTraceElement[] getAllocationStackTrace() {
		return allocationStackTrace;
	}

	public void setAllocationStackTrace(StackTraceElement[] allocationStackTrace) {
		this.allocationStackTrace = allocationStackTrace;
	}
}
