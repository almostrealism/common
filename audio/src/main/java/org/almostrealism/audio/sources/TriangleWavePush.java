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

package org.almostrealism.audio.sources;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * Generates a single triangle wave sample.
 * Computes the output as a linear ramp from -1 to +1 and back to -1 over
 * each wave cycle. The triangle wave has no discontinuities so requires
 * no anti-aliasing, producing a mellow, flute-like tone.
 *
 * @see WaveCellComputation
 * @see TriangleWaveCell
 */
public class TriangleWavePush extends WaveCellComputation implements ExpressionFeatures {

	public TriangleWavePush(SineWaveCellData data, Producer<PackedCollection> envelope,
							PackedCollection output) {
		super(data, envelope, output);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		purgeVariables();

		Expression<?> t = wavePosition().add(phase());
		Expression<?> frac = t.subtract(t.floor());

		Expression<?> triangle = conditional(frac.lessThan(e(0.5)),
				frac.multiply(e(4.0)).subtract(e(1.0)),
				e(3.0).subtract(frac.multiply(e(4.0))));

		addVariable(output().assign(
				envelope().multiply(amplitude()).multiply(triangle.multiply(depth()))
		));
	}
}
