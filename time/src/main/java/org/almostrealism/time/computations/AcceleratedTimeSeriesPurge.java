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

package org.almostrealism.time.computations;

import io.almostrealism.code.HybridScope;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.DynamicOperationComputationAdapter;
import io.almostrealism.relation.Producer;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;

import java.util.function.Consumer;

public class AcceleratedTimeSeriesPurge extends DynamicOperationComputationAdapter {
	private double wavelength;

	public AcceleratedTimeSeriesPurge(Producer<AcceleratedTimeSeries> series, Producer<CursorPair> cursors, double frequency) {
		super(new Producer[] { series, cursors, () -> new Provider<>(new Scalar()) });
		this.wavelength = 1.0 / frequency;
	}

	@Override
	public Scope<Void> getScope() {
		HybridScope<Void> scope = new HybridScope<>(this);

		String left = getArgument(0).valueAt(0).getExpression();
		String right = getArgument(0).valueAt(1).getExpression();
		String banki = getArgument(0).get("2 * i").getExpression();
		String cursor0 = getArgument(1).valueAt(0).getExpression();
		String count = getArgument(2).valueAt(0).getExpression();

		Consumer<String> code = scope.code();
		if (wavelength != 1.0) {
			code.accept(count + " = fmod(" + count + " + " + stringForDouble(1.0) + ", " + stringForDouble(wavelength) + ");");
			code.accept("if (" + count + " = " + stringForDouble(0.0) + ") {\n");
		}

		code.accept("if (" + right + " - " + left + " > 0) {\n");
		code.accept("for (int i = " + left + " + 1; i < " + right + "; i++) {\n");
		code.accept("	if (" + cursor0 + " > " + banki + ") {\n");
		code.accept("		" + left + " = i;\n");
		// code.accept("		break;\n");
		code.accept("	}\n");
		code.accept("}\n");
		code.accept("}\n");

		if (wavelength != 1.0) code.accept("}\n");
		return scope;
	}
}
