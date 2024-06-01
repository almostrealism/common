/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.time.computations.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.computations.MultiOrderFilter;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class MultiOrderFilterTest implements TestFeatures {
//	static {
//		NativeCompiler.enableInstructionSetMonitoring = !TestSettings.skipLongTests;
//		MetalProgram.enableProgramMonitoring = !TestSettings.skipLongTests;
//	}

	@Test
	public void compile() {
		int order = 30;

		PackedCollection<?> series = new PackedCollection<>(10000);
		PackedCollection<?> coefficients = new PackedCollection<>(order + 1);

		MultiOrderFilter filter = MultiOrderFilter.create(traverseEach(cp(series)), p(coefficients));
		filter.get().evaluate();
	}
}
