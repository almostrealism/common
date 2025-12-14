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

package org.almostrealism.algebra.computations.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class ScalarBankSumTest implements TestFeatures {
	@Test(timeout = 30000)
	public void sum() {
		PackedCollection bank = new PackedCollection(shape(4, 2));
		bank.setMem(0, 1, 0);
		bank.setMem(2, 2, 0);
		bank.setMem(4, 3, 0);
		bank.setMem(6, 4, 0);

		Producer<PackedCollection> s = subset(shape(4, 1), p(bank), 0).sum();
		assertEquals(10, s.get().evaluate());
	}
}
