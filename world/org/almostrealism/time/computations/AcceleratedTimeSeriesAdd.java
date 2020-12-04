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

import io.almostrealism.code.ExplicitScope;
import io.almostrealism.code.Scope;
import org.almostrealism.hardware.DynamicAcceleratedOperationAdapter;
import org.almostrealism.relation.Evaluable;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.relation.Producer;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;
import org.almostrealism.time.TemporalScalar;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class AcceleratedTimeSeriesAdd extends DynamicAcceleratedOperationAdapter<AcceleratedTimeSeries> {
	public AcceleratedTimeSeriesAdd(Producer<AcceleratedTimeSeries> series, Producer<TemporalScalar> addition) {
		super(new Supplier[] { series, addition } );
	}

	@Override
	public Scope<Void> getScope(NameProvider provider) {
		ExplicitScope<Void> scope = new ExplicitScope<>(this);

		String bank0 = getArgumentValueName(0, 0);
		String bank1 = getArgumentValueName(0, 1);
		String banklast0 = getArgumentValueName(0, "2 * (int)" + bank1);
		String banklast1 = getArgumentValueName(0, "2 * (int)" + bank1 + " + 1");
		String input0 = getArgumentValueName(1, 0);
		String input1 = getArgumentValueName(1, 1);

		Consumer<String> code = scope.code();
		code.accept(bank1 + " = " + bank1 + " + 1;\n");
		code.accept(banklast0 + " = " + input0 + ";\n");
		code.accept(banklast1 + " = " + input1 + ";\n");
		return scope;
	}
}
