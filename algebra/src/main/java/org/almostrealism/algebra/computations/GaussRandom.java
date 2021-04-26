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

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.HybridScope;
import io.almostrealism.code.Scope;
import io.almostrealism.code.expressions.Expression;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import org.almostrealism.hardware.Hardware;

import java.util.function.Consumer;
import java.util.function.IntFunction;

public class GaussRandom extends DynamicProducerComputationAdapter<Pair, Scalar> implements ScalarProducer {
	public GaussRandom() {
		super(2, Scalar.blank(), ScalarBank::new, PairFeatures.getInstance().rand());
	}

	@Override
	public Scope<Scalar> getScope() {
		String pi = Hardware.getLocalHardware().isDoublePrecision() ? "M_PI" : "M_PI_F";

		HybridScope<Scalar> scope = new HybridScope<>(this);

		String randx = getArgument(1).get(0).getExpression();
		String randy = getArgument(1).get(1).getExpression();
		String result = ((ArrayVariable) getOutputVariable()).get(0).getExpression();

		Consumer<String> code = scope.code();
		code.accept(result + " = sqrt(-2 * log(" + randx + ")) * cos(2 * " + pi + " * " + randy + ");\n");

		return scope;
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		String pi = Hardware.getLocalHardware().isDoublePrecision() ? "M_PI" : "M_PI_F";

		String randx = getArgument(1).get(0).getExpression();
		String randy = getArgument(1).get(1).getExpression();

		return i -> i == 0 ?
				new Expression<>(Double.class,
						"sqrt(-2 * log(" + randx + ")) * cos(2 * " + pi + " * " + randy + ")",
							getArgument(0), getArgument(1))
				: new Expression<>(Double.class, stringForDouble(1.0));
	}
}
