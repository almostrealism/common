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

package org.almostrealism.hardware.jni;

import io.almostrealism.code.NameProvider;
import io.almostrealism.code.PhysicalScope;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.KernelSupport;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.cl.CLMemory;
import org.jocl.cl_mem;

import java.io.IOException;
import java.util.stream.Stream;

public interface NativeSupport<T extends NativeLibrary> extends KernelSupport, NameProvider { // Supplier<T> {
	default void initNativeFunctionName() {
		setFunctionName("Java_" +
				getClass().getName().replaceAll("\\.", "_") +
				"_apply");
	}

	void setFunctionName(String name);

	/**
	 * @return  GLOBAL
	 */
	@Override
	default PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	@Override
	default String getVariablePrefix() { return getClass().getSimpleName(); }

	@Override
	default boolean isKernelEnabled() { return false; }

	default void initNative() {
		initNativeFunctionName();

		try {
			Hardware.getLocalHardware().loadNative(this);
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	NativeLibrary get();

	default void apply(MemoryData... args) {
		apply(Stream.of(args).map(MemoryData::getMem).toArray(CLMemory[]::new),
				Stream.of(args).mapToInt(MemoryData::getOffset).toArray(),
				Stream.of(args).mapToInt(MemoryData::getMemLength).toArray());
	}

	default void apply(CLMemory args[], int offsets[], int sizes[]) {
		apply(Hardware.getLocalHardware().getQueue().getNativePointer(),
				Stream.of(args).mapToLong(CLMemory::getNativePointer).toArray(),
				offsets, sizes, args.length);
	}

	void apply(long commandQueue, long arg[], int offset[], int size[], int count);
}
