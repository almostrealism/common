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

import io.almostrealism.code.Computation;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.Compilation;
import org.almostrealism.hardware.MemoryData;

import java.util.stream.Stream;

public class NativeComputationEvaluable<T extends MemoryData> extends AcceleratedComputationEvaluable<T> implements NativeLibrary {
	public NativeComputationEvaluable(Computation<T> c) {
		super(c, false);
		setCompilation(Compilation.JNI);
	}

	@Override
	public Object[] apply(Object[] args) {
		args = getAllArgs(args);
		((NativeSupport) getComputation()).apply(
				Stream.of(args).map(o -> (MemoryData) o).toArray(MemoryData[]::new));
		return args;
	}
}
