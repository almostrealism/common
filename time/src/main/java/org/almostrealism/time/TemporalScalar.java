/*
 * Copyright 2020 Michael Murray
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

import org.almostrealism.algebra.Pair;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.PooledMem;
import io.almostrealism.relation.Producer;

import java.util.function.BiFunction;

public class TemporalScalar extends Pair<TemporalScalar> {
	public TemporalScalar() { }

	public TemporalScalar(double time, double value) {
		setTime(time);
		setValue(value);
	}

	public TemporalScalar(MemoryData delegate, int delegateOffset) {
		super(delegate, delegateOffset);
	}

	public double getTime() { return getA(); }

	public void setTime(double time) { setA(time); }

	public double getValue() { return getB(); }

	public void setValue(double value) { setB(value); }

	public static BiFunction<MemoryData, Integer, Pair<?>> postprocessor() {
		return (delegate, offset) -> new TemporalScalar(delegate, offset);
	}
}
