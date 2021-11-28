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

package org.almostrealism.algebra;

import io.almostrealism.code.expressions.MultiExpression;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.computations.DefaultPairEvaluable;
import io.almostrealism.code.ProducerComputation;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.DefaultComputer;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;

import java.util.function.Supplier;

public interface PairProducer extends ProducerComputation<Pair>, KernelizedProducer<Pair>,
										MultiExpression<Double>, PairFeatures {
	@Override
	default KernelizedEvaluable<Pair> get() {
		DefaultComputer computer = Hardware.getLocalHardware().getComputer();

		AcceleratedComputationEvaluable ev;

		if (computer.isNative()) {
			ev = (AcceleratedComputationEvaluable) computer.compileProducer(this);
		} else {
			ev = new DefaultPairEvaluable(this);
		}

		ev.compile();
		return ev;
	}

	default ScalarProducer l() { return l(this); }
	default ScalarProducer r() { return r(this); }

	default ScalarProducer x() { return l(this); }
	default ScalarProducer y() { return r(this); }

	default PairProducer multiplyComplex(Supplier<Evaluable<? extends Pair>> p) {
		return multiplyComplex(this, p);
	}
}
