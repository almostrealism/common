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

package org.almostrealism.audio.computations;

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;

import java.util.function.Consumer;

@Deprecated
public class DefaultEnvelopeComputation extends CollectionProducerComputationBase implements ProducerComputation<PackedCollection> {

	public DefaultEnvelopeComputation(Producer<PackedCollection> notePosition) {
		super(null, new TraversalPolicy(1), notePosition);
	}

	@Override
	public Scope<PackedCollection> getScope(KernelStructureContext context) {
		HybridScope<PackedCollection> scope = new HybridScope<>(this);

		String position = getArgument(1).valueAt(0).getSimpleExpression(getLanguage());
		String result = ((ArrayVariable) getOutputVariable()).valueAt(0).getSimpleExpression(getLanguage());

		Consumer<String> code = scope.code();
		code.accept("if (" + position + " > 1.0) {\n");
		code.accept("	" + result + " = 0.0;\n");
		code.accept("} else if (" + position + " < 0.1) {\n");
		code.accept("	" + result + " = " + position + " / 0.1;\n");
		code.accept("} else {\n");
		code.accept("	" + result + " = cos(" + position + " * M_PI_2);\n");
		code.accept("}\n");

		return scope;
	}
}
