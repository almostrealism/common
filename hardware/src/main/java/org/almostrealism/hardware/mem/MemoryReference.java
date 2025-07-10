package org.almostrealism.hardware.mem;

import io.almostrealism.code.Memory;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

public abstract class MemoryReference<T extends Memory> extends WeakReference<T> {
	public MemoryReference(T referent, ReferenceQueue<? super T> q) {
		super(referent, q);
	}
}
