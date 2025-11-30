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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public class SilenceDetectionOutputLine implements OutputLine, CellFeatures {
	private final OutputLine out;
	private final double threshold;
	private final PackedCollection max;

	public SilenceDetectionOutputLine(OutputLine out) {
		this(out, 0.05);
	}

	public SilenceDetectionOutputLine(OutputLine out, double threshold) {
		this.out = out;
		this.threshold = threshold;
		this.max = new PackedCollection(1);
	}

	@Override
	public void write(PackedCollection sample) {
		throw new UnsupportedOperationException();
	}

	public boolean isSilence() {
		return max.toDouble() < threshold;
	}

	@Override
	public Supplier<Runnable> write(Producer<PackedCollection> frames) {
		OperationList write = new OperationList("SilenceDetectionOutputLine");
		write.add(a(cp(max), max(traverse(0, frames))));
		write.add(out.write(frames));
		return write;
	}
}
