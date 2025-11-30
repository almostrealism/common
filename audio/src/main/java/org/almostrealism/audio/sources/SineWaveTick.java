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

import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

public class SineWaveTick extends SineWaveComputation {
	public SineWaveTick(SineWaveCellData data, Producer<PackedCollection> envelope) {
		super(data, envelope, new PackedCollection(1));
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		purgeVariables();
		addVariable(wavePosition().assign(wavePosition().add(waveLength())));
		addVariable(notePosition().assign(notePosition().add(noteLength().reciprocal())));
	}
}
