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

package org.almostrealism.time;

import io.almostrealism.code.Computation;
import io.almostrealism.cycle.Setup;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Lifecycle;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Loop;
import org.almostrealism.time.computations.Interpolate;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface TemporalFeatures {
	default Frequency bpm(double bpm) {
		return Frequency.forBPM(bpm);
	}

	default Supplier<Runnable> iter(Temporal t, int iter) {
		return iter(t, iter, true);
	}

	default Supplier<Runnable> iter(Temporal t, int iter, boolean resetAfter) {
		Supplier<Runnable> tick = loop(t, iter);

		if (t instanceof Lifecycle || t instanceof Setup) {
			OperationList o = new OperationList("TemporalFeature Iteration");
			if (t instanceof Setup) o.add(((Setup) t).setup());
			o.add(tick);
			if (resetAfter && t instanceof Lifecycle) o.add(() -> ((Lifecycle) t)::reset);
			return o;
		} else {
			return tick;
		}
	}

	default Supplier<Runnable> loop(Temporal t, int iter) {
		Supplier<Runnable> tick = t.tick();

		if ((tick instanceof OperationList && !((OperationList) tick).isComputation())
				|| !(tick instanceof Computation)) {
			Runnable r = tick.get();
			return () -> () -> IntStream.range(0, iter).forEach(i -> r.run());
		} else {
			return new Loop((Computation<Void>) tick, iter);
		}
	}
	
	default Interpolate interpolate(Producer<PackedCollection<?>> series,
									Producer<PackedCollection<?>> position,
									Producer<PackedCollection<?>> rate) {
		return new Interpolate(series, position, rate);
	}

	default <T extends PackedCollection<?>> Interpolate interpolate(
									Producer<PackedCollection<?>> series,
									Producer<PackedCollection<?>> position,
									Producer<PackedCollection<?>> rate,
									Function<Expression, Expression> timeForIndex) {
		return new Interpolate(series, position, rate, timeForIndex);
	}
}
