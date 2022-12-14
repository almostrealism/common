/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.code.HybridScope;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.ProducerComputationAdapter;
import io.almostrealism.scope.Scope;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.function.Supplier;

public class ScalarBankSum extends ProducerComputationAdapter<ScalarBank, Scalar> implements ScalarProducer, DestinationSupport<Scalar>, ComputerFeatures {
	private final int count;

	private Supplier<Scalar> destination;

	public ScalarBankSum(int count, Supplier<Evaluable<? extends ScalarBank>> input) {
		this.count = count;
		this.destination = Scalar::new;
		this.setInputs(new MemoryDataDestination(this, ScalarBank::new), input);
		init();
	}

	@Override
	public void setDestination(Supplier<Scalar> destination) { this.destination = destination; }

	@Override
	public Supplier<Scalar> getDestination() { return destination; }

	/**
	 * @return  PhysicalScope#GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	@Override
	public Scope<Scalar> getScope() {
		HybridScope<Scalar> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "ScalarBankSum"));

		String i = getVariablePrefix() + "_i";
		String result = getArgument(0, 2).valueAt(0).getExpression();
		String value = getArgument(1, 2 * count).get("2 * " + i).getExpression();

		scope.code().accept("for (int " + i + " = 0; " + i + " < " + count +"; " + i + "++) {\n");
		scope.code().accept("    " + result + " = " + result + " + " + value + ";\n");
		scope.code().accept("}\n");
		return scope;
	}
}
