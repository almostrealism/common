/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.graph.temporal;

import io.almostrealism.code.HybridScope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Consumer;

public class WaveCellPush extends WaveCellComputation {
	public WaveCellPush(WaveCellData data, PackedCollection<?> wave, Producer<Scalar> frame, Scalar output) {
		super(data, wave, frame, output);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		scope = new HybridScope(this);

		Consumer<String> exp = scope.code();
		exp.accept("if ((");
		exp.accept(getWavePosition().valueAt(0).getSimpleExpression());
		exp.accept(" >= 0) & (");
		exp.accept(getWavePosition().valueAt(0).getSimpleExpression());
		exp.accept(" < ");
		exp.accept(getWaveCount().valueAt(0).getSimpleExpression());
		exp.accept(")) {\n");
		exp.accept(getOutput().valueAt(0).getSimpleExpression());
		exp.accept(" = ");
		exp.accept(getAmplitude().valueAt(0).getSimpleExpression());
		exp.accept(" * ");
//		exp.accept(getWave().get("(" + getWaveIndex().valueAt(0).getExpression() +
//				" + floor(" + getWavePosition().valueAt(0).getExpression() + "))").getExpression());
		exp.accept(getWave().getRelative(getWaveIndex().valueAt(0).add(getWavePosition().valueAt(0).floor())).getSimpleExpression());
		exp.accept(";\n");
		exp.accept("} else {\n");
		exp.accept(getOutput().valueAt(0).getSimpleExpression());
		exp.accept(" = 0.0;\n");
		exp.accept("}\n");
	}
}
