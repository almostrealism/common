/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.audio.arrange;

import io.almostrealism.cycle.Setup;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.time.Temporal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class GlobalTimeManager implements Setup, Temporal, ConsoleFeatures {
	public static final int MAX_RESETS = 32;

	private final TimeCell clock;
	private final List<Integer> resets;
	private final IntUnaryOperator frameForMeasure;

	public GlobalTimeManager(IntUnaryOperator frameForMeasure) {
		this.clock = new TimeCell(MAX_RESETS);
		this.frameForMeasure = frameForMeasure;
		this.resets = new ArrayList<>();
	}

	public TimeCell getClock() {
		return clock;
	}

	public void addReset(int measure) {
		if (resets.size() >= MAX_RESETS) throw new IllegalArgumentException("Maximum number of resets exceeded");
		resets.add(measure);
		resets.sort(Integer::compareTo);
	}

	public List<Integer> getResets() { return resets; }

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("GlobalTimeManager Setup");
		setup.add(() -> () -> {
			IntStream.range(0, resets.size()).forEach(i -> clock.setReset(i, frameForMeasure.applyAsInt(resets.get(i))));
		});
		setup.add(clock.setup());
		return setup;
	}

	@Override
	public Supplier<Runnable> tick() {
		return clock.tick();
	}

	@Override
	public Console console() { return CellFeatures.console; }
}
