/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.line;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.TemporalRunner;

public class AudioLineMix implements AudioLineOperation, CellFeatures {
	private final AudioLineOperation operation;
	private final double cleanLevel;

	public AudioLineMix(AudioLineOperation operation, double cleanLevel) {
		this.operation = operation;
		this.cleanLevel = cleanLevel;

		if (cleanLevel > 1.0 || cleanLevel < 0.0) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public TemporalRunner process(Producer<PackedCollection> input,
								  Producer<PackedCollection> output,
								  int frames) {
		PackedCollection temp = new PackedCollection(frames);
		TemporalRunner wetRunner = operation.process(input, p(temp), frames);

		CollectionProducer dry = c(input).multiply(cleanLevel);
		CollectionProducer wet = cp(temp).multiply(1.0 - cleanLevel);

		OperationList op = new OperationList("AudioLineMix");
		op.add(wetRunner.tick());
		op.add(a(c(output).each(), add(dry.each(), wet.each())));

		return new TemporalRunner(wetRunner.setup(), op);
	}
}
