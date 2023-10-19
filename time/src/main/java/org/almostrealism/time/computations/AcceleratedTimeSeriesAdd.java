/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.scope.HybridScope;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.OperationComputationAdapter;
import io.almostrealism.relation.Producer;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.TemporalScalar;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class AcceleratedTimeSeriesAdd extends OperationComputationAdapter<AcceleratedTimeSeries> {
	public AcceleratedTimeSeriesAdd(Producer<AcceleratedTimeSeries> series, Producer<TemporalScalar> addition) {
		super(new Supplier[] { series, addition } );
	}

	@Override
	public Scope<Void> getScope() {
		HybridScope<Void> scope = new HybridScope<>(this);

		Expression<?> bank1 = getArgument(0).valueAt(1);
		String banklast0 = getArgument(0).referenceRelative(bank1.toInt().multiply(2)).getSimpleExpression(getLanguage());
		String banklast1 = getArgument(0).referenceRelative(bank1.toInt().multiply(2).add(1)).getSimpleExpression(getLanguage());
		String input0 = getArgument(1).valueAt(0).getSimpleExpression(getLanguage());
		String input1 = getArgument(1).valueAt(1).getSimpleExpression(getLanguage());

		Consumer<String> code = scope.code();
		code.accept(banklast0 + " = " + input0 + ";\n");
		code.accept(banklast1 + " = " + input1 + ";\n");
		code.accept(bank1.getSimpleExpression(getLanguage()) + " = " + bank1.getSimpleExpression(getLanguage()) + " + 1.0;\n");
		return scope;
	}
}
