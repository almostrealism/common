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

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.computations.Dither;
import org.almostrealism.algebra.computations.PowerSpectrum;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.jni.NativeComputationEvaluable;
import org.almostrealism.hardware.jni.NativeSupport;

import java.util.function.Supplier;

public abstract class NativeDither extends Dither implements NativeSupport<NativeComputationEvaluable> {
	public NativeDither(int count) {
		super(count, new PassThroughProducer(2 * count, 0), new PassThroughProducer<>(2, 1));
		initNative();
	}

	public NativeDither(int count, Supplier<Pair> randDestination) {
		super(count, new PassThroughProducer(2 * count, 0), new PassThroughProducer<>(2, 1), randDestination);
		initNative();
	}

	@Override
	public NativeComputationEvaluable<ScalarBank> get() {
		return new NativeComputationEvaluable<>(this);
	}
}
