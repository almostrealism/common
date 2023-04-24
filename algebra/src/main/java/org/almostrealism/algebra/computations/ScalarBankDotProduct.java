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

package org.almostrealism.algebra.computations;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarBankFeatures;

import java.util.function.Supplier;

@Deprecated
public class ScalarBankDotProduct extends ScalarBankSum {
	public ScalarBankDotProduct(int count, Supplier<Evaluable<? extends ScalarBank>> a,
								Supplier<Evaluable<? extends ScalarBank>> b) {
		super(count, product(count, a, b));
	}

	private static Producer<ScalarBank> product(int count, Supplier<Evaluable<? extends ScalarBank>> a,
												Supplier<Evaluable<? extends ScalarBank>> b) {
		return ScalarBankFeatures.getInstance().scalarBankProduct(count, a, b);
	}
}
