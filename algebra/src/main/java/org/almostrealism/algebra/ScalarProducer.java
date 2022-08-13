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

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Evaluable;

import org.almostrealism.algebra.computations.DefaultScalarEvaluable;
import org.almostrealism.algebra.computations.ScalarPow;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.bool.AcceleratedConditionalStatementScalar;
import org.almostrealism.bool.AcceleratedConditionalStatementVector;
import org.almostrealism.bool.GreaterThanScalar;
import org.almostrealism.bool.GreaterThanVector;
import org.almostrealism.bool.LessThanScalar;
import org.almostrealism.bool.LessThanVector;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.DefaultComputer;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;

import java.util.function.Supplier;

public interface ScalarProducer extends ScalarProducerBase {
	@Override
	default KernelizedEvaluable<Scalar> get() {
		DefaultComputer computer = (DefaultComputer) Hardware.getLocalHardware().getComputeContext().getComputer();

		AcceleratedComputationEvaluable ev;

		if (computer.isNative()) {
			ev = (AcceleratedComputationEvaluable) computer.compileProducer(this);
		} else {
			ev = new DefaultScalarEvaluable(this);
		}

		ev.compile();
		return ev;
	}
}
