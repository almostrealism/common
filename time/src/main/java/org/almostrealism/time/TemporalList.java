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

package org.almostrealism.time;

import io.almostrealism.uml.Lifecycle;
import org.almostrealism.hardware.OperationList;

import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class TemporalList extends ArrayList<Temporal> implements Temporal, Lifecycle {

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("TemporalList Tick");
		stream().map(Temporal::tick).forEach(tick::add);
		return tick;
	}

	@Override
	public void reset() {
		Lifecycle.super.reset();
		forEach(t -> {
			if (t instanceof Lifecycle) ((Lifecycle) t).reset();
		});
	}

	public static Collector<Temporal, ?, TemporalList> collector() {
		return Collectors.toCollection(TemporalList::new);
	}
}
