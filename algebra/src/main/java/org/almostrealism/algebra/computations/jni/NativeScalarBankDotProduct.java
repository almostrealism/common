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

package org.almostrealism.algebra.computations.jni;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.computations.ScalarBankDotProduct;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.jni.NativeComputationEvaluable;
import org.almostrealism.hardware.jni.NativeSupport;

public abstract class NativeScalarBankDotProduct extends ScalarBankDotProduct implements NativeSupport<NativeComputationEvaluable> {
	public NativeScalarBankDotProduct(int count) {
		super(count, new PassThroughProducer(2 * count, 0),
				new PassThroughProducer<>(2 * count, 1));
		initNative();
	}

	@Override
	public NativeComputationEvaluable<Scalar> get() {
		return new NativeComputationEvaluable<>(this);
	}
}
