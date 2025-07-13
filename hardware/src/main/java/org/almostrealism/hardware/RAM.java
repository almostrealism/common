/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.Memory;
import org.almostrealism.io.SystemUtils;

import java.util.stream.Stream;

public abstract class RAM implements Memory {
	public static boolean enableWarnings = SystemUtils.isEnabled("AR_HARDWARE_MEMORY_WARNINGS").orElse(true);
	public static int allocationTraceFrames = SystemUtils.getInt("AR_HARDWARE_ALLOCATION_TRACE_FRAMES").orElse(8);

	private final StackTraceElement[] allocationStackTrace;

	protected RAM() {
		this(allocationTraceFrames);
	}

	protected RAM(int traceFrames) {
		if (traceFrames > 0) {
			allocationStackTrace = Stream.of(Thread.currentThread().getStackTrace())
					.limit(traceFrames).toArray(StackTraceElement[]::new);
		} else {
			allocationStackTrace = null;
		}
	}

	public long getContainerPointer() {
		return getContentPointer();
	}

	public long getContentPointer() {
		throw new UnsupportedOperationException();
	}

	public long getSize() { throw new UnsupportedOperationException(); }

	public StackTraceElement[] getAllocationStackTrace() {
		return allocationStackTrace;
	}

	@Override
	public String toString() {
		return String.format("%s[%d]", getClass().getSimpleName(), getSize());
	}
}
