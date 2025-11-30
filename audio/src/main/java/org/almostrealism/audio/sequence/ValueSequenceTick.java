/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.sequence;

import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.HybridScope;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.temporal.BaseAudioData;

import java.util.function.Consumer;

public class ValueSequenceTick extends ValueSequenceComputation {
	public ValueSequenceTick(BaseAudioData data,
							 Producer<PackedCollection> durationFrames,
							 Producer<PackedCollection>... choices) {
		this(data, durationFrames, true, choices);
	}

	public ValueSequenceTick(BaseAudioData data,
							 Producer<PackedCollection> durationFrames,
							 boolean repeat, Producer<PackedCollection>... choices) {
		super(data, durationFrames, new PackedCollection(1), repeat, choices);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		scope = new HybridScope(this);

		Consumer<String> exp = scope.code();

		exp.accept(getWavePosition().reference(e(0)).getSimpleExpression(getLanguage()));
		exp.accept(" = ");
		exp.accept(getWavePosition().valueAt(0).add(getWaveLength().valueAt(0)).getSimpleExpression(getLanguage()));
		exp.accept(";\n");

		if (repeat) {
			exp.accept(getWavePosition().reference(e(0)).getSimpleExpression(getLanguage()));
			exp.accept(" = fmod(");
			exp.accept(getWavePosition().valueAt(0).getSimpleExpression(getLanguage()));
			exp.accept(", ");
			exp.accept(getDurationFrames().valueAt(0).getSimpleExpression(getLanguage()));
			exp.accept(");\n");
		}
	}
}
