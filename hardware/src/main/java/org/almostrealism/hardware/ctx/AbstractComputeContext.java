/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware.ctx;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.profile.CompilationProfile;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.MemoryData;

import java.util.function.Supplier;

public abstract class AbstractComputeContext<T extends DataContext<MemoryData>> implements ComputeContext<MemoryData> {
	public static CompilationProfile compilationProfile;

	private final T dc;

	protected AbstractComputeContext(T dc) {
		this.dc = dc;
	}

	public T getDataContext() { return dc; }

	protected void recordCompilation(Scope<?> scope, Supplier<String> source, long nanos) {
		if (compilationProfile != null) {
			compilationProfile.recordCompilation(scope.getMetadata(), source.get(), nanos);
		}
	}
}
