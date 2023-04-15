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

import io.almostrealism.code.HybridScope;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.scope.Scope;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarBankProducerBase;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.CollectionProducerComputationAdapter;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.MemoryData;

import java.util.function.Supplier;

public class ScalarBankPad extends CollectionProducerComputationAdapter<ScalarBank, ScalarBank> implements ScalarBankProducerBase, DestinationSupport<ScalarBank>, ComputerFeatures {
	private final int count;
	private final int total;

	private Supplier<ScalarBank> destination;

	public ScalarBankPad(int count, int total, Supplier<Evaluable<? extends ScalarBank>> input) {
		super(new TraversalPolicy(count, 2), input);
		this.count = count;
		this.total = total;
	}

	@Override
	public void setDestination(Supplier<ScalarBank> destination) { this.destination = destination; }

	@Override
	public Supplier<ScalarBank> getDestination() { return destination; }

	/**
	 * @return  PhysicalScope#GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	@Override
	public Scope<ScalarBank> getScope() {
		HybridScope<ScalarBank> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "ScalarBankPad"));

		String i = getVariablePrefix() + "_i";
		String resultX = getArgument(0, 2 * count).get("2 * " + i).getExpression();
		String resultY = getArgument(0, 2 * count).get("2 * " + i + " + 1").getExpression();
		String valueX = getArgument(1, 2 * count).get("2 * " + i).getExpression();
		String valueY = getArgument(1, 2 * count).get("2 * " + i + " + 1").getExpression();

		scope.code().accept("for (int " + i + " = 0; " + i + " < " + count +"; " + i + "++) {\n");
		scope.code().accept("    if (" + i + " < " + total + ") {\n");
		scope.code().accept("        " + resultX + " = " + valueX + ";\n");
		scope.code().accept("        " + resultY + " = " + valueY + ";\n");
		scope.code().accept("    } else {\n");
		scope.code().accept("        " + resultX + " = " + stringForDouble(0.0) + ";\n");
		scope.code().accept("        " + resultY + " = " + stringForDouble(1.0) + ";\n");
		scope.code().accept("    }\n");
		scope.code().accept("}\n");
		return scope;
	}

	@Override
	public ScalarBank postProcessOutput(MemoryData output, int offset) {
		return new ScalarBank(output.getMemLength() / 2, output, offset);
	}
}
