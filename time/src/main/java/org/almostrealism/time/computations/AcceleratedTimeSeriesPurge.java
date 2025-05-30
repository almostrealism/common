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

package org.almostrealism.time.computations;

import io.almostrealism.code.Precision;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;
import io.almostrealism.relation.Producer;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class AcceleratedTimeSeriesPurge extends OperationComputationAdapter<PackedCollection<?>> {
	private double wavelength;

	public AcceleratedTimeSeriesPurge(Producer<AcceleratedTimeSeries> series, Producer<CursorPair> cursors, double frequency) {
		super(new Producer[] { series, cursors, () -> new Provider<>(new Scalar()) });
		this.wavelength = 1.0 / frequency;
	}

	private AcceleratedTimeSeriesPurge(double wavelength, Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(arguments);
		this.wavelength = wavelength;
	}

	@Override
	public ParallelProcess<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		return new AcceleratedTimeSeriesPurge(wavelength, children.toArray(Supplier[]::new));
	}

	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		HybridScope<Void> scope = new HybridScope<>(this);

		Expression i = new StaticReference(Integer.class, "i");
		String left = getArgument(0).valueAt(0).getSimpleExpression(getLanguage());
		String right = getArgument(0).valueAt(1).getSimpleExpression(getLanguage());
		String banki = getArgument(0).referenceRelative(i.multiply(2)).getSimpleExpression(getLanguage());
		String cursor0 = getArgument(1).valueAt(0).getSimpleExpression(getLanguage());
		String count = getArgument(2).valueAt(0).getSimpleExpression(getLanguage());

		Precision p = getLanguage().getPrecision();

		Consumer<String> code = scope.code();
		if (wavelength != 1.0) {
			code.accept(count + " = fmod(" + count + " + " + p.stringForDouble(1.0) + ", " + p.stringForDouble(wavelength) + ");");
			code.accept("if (" + count + " = " + p.stringForDouble(0.0) + ") {\n");
		}

		code.accept("if (" + right + " - " + left + " > 0) {\n");
		code.accept("for (int i = " + left + " + 1; i < " + right + "; i++) {\n");
		code.accept("	if (" + cursor0 + " > " + banki + ") {\n");
		code.accept("		" + left + " = i;\n");
		code.accept("	}\n");
		code.accept("	if (" + cursor0 + " < " + banki + ") {\n");
		code.accept("		break;\n");
		code.accept("	}\n");
		code.accept("}\n");
		code.accept("}\n");

		if (wavelength != 1.0) code.accept("}\n");
		return scope;
	}
}
