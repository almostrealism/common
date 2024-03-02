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

package org.almostrealism.collect.test;

import io.almostrealism.collect.RepeatOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class CollectionOrderingTests implements TestFeatures {
	@Test
	public void repeatOrdering() {
		PackedCollection<?> root = pack(2.0, 3.0, 1.0);
		PackedCollection<?> repeated = new PackedCollection<>(shape(4, 3), 1, root, 0, new RepeatOrdering(3));
		repeated.print();
	}
}
