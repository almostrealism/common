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

package org.almostrealism.algebra.computations;

import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarBankProducer;

public class StaticScalarBankComputation extends StaticComputationAdapter<ScalarBank> implements ScalarBankProducer {
	public StaticScalarBankComputation(ScalarBank value) {
		super(value, () -> args -> new ScalarBank(value.getCount()), i -> { throw new UnsupportedOperationException(); });
	}
}
