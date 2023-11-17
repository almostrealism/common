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

package io.almostrealism.kernel;

import io.almostrealism.relation.Series;

import java.util.List;
import java.util.OptionalInt;

public class KernelSeries implements Series {
	private OptionalInt period;
	private OptionalInt scale;
	private int inverseScale;

	protected KernelSeries() {
		this(OptionalInt.empty(), OptionalInt.empty());
	}

	protected KernelSeries(OptionalInt period, OptionalInt scale) {
		this(period, scale, 1);
	}

	protected KernelSeries(OptionalInt period, OptionalInt scale, int inverseScale) {
		this.period = period;
		this.scale = scale;
		this.inverseScale = inverseScale;
	}

	@Override
	public boolean isFinite() { return false; }

	@Override
	public OptionalInt getPeriod() { return period; }

	public OptionalInt getScale() { return scale; }

	public KernelSeries scale(int scale) {
		if (scale % inverseScale == 0) {
			return new KernelSeries(
					getPeriod().isPresent() ? OptionalInt.of(getPeriod().getAsInt() * scale / inverseScale) : OptionalInt.empty(),
					getScale().isPresent() ? OptionalInt.of(getScale().getAsInt() * scale / inverseScale) : OptionalInt.empty());
		} else {
			return new KernelSeries(
					getPeriod().isPresent() ? OptionalInt.of(getPeriod().getAsInt() * scale) : OptionalInt.empty(),
					getScale().isPresent() ? OptionalInt.of(getScale().getAsInt() * scale) : OptionalInt.empty(),
					inverseScale);
		}
	}

	public KernelSeries loop(int period) {
		period = Math.abs(period);

		int inverseScale = this.inverseScale;

		if (period % inverseScale == 0) {
			period = period / inverseScale;
			inverseScale = 1;
		}

		if (getPeriod().isPresent()) {
			return new KernelSeries(
					OptionalInt.of(getPeriod().getAsInt() * period),
					getScale().isPresent() ? OptionalInt.of(getScale().getAsInt() * period) : OptionalInt.empty(),
					inverseScale);
		} else {
			return new KernelSeries(
					getScale().isPresent() ? OptionalInt.of(getScale().getAsInt() * period) : OptionalInt.empty(),
					getScale().isPresent() ? OptionalInt.of(getScale().getAsInt() * period) : OptionalInt.empty(),
					inverseScale);
		}
	}

	public static KernelSeries infinite() {
		return new KernelSeries(OptionalInt.empty(), OptionalInt.empty());
	}

	public static KernelSeries infinite(int scale) {
		return new KernelSeries(OptionalInt.empty(), OptionalInt.of(scale));
	}

	public static KernelSeries constant(double value) {
		value = Math.abs(value);

		if (value < 1.0) {
			return new KernelSeries(OptionalInt.of(1), OptionalInt.of((int) Math.ceil(1.0 / value)));
		} else if (value == Math.floor(value)) {
			return new KernelSeries(OptionalInt.of(1), OptionalInt.of(1), (int) value);
		} else {
			return new KernelSeries(OptionalInt.of(1), OptionalInt.of(1));
		}
	}

	public static KernelSeries periodic(List<Integer> periods) {
		// TODO  This is actually too large, the period should be the LCM of the periods
		int period = periods.stream().distinct().reduce(1, (a, b) -> a * b);
		return new KernelSeries(OptionalInt.of(period), OptionalInt.of(period));
	}

	public static KernelSeries product(List<KernelSeries> series) {
		int scale = series.stream()
				.map(k -> k.getScale())
				.filter(o -> o.isPresent())
				.mapToInt(o -> o.getAsInt())
				.reduce(1, (a, b) -> a * b);
		int inverseScale = series.stream()
				.map(k -> k.inverseScale)
				.reduce(1, (a, b) -> a * b);

		if (scale % inverseScale == 0) {
			scale = scale / inverseScale;
			return new KernelSeries(OptionalInt.empty(), OptionalInt.of((int) Math.ceil(scale)));
		} else {
			return new KernelSeries(OptionalInt.empty(), OptionalInt.of(scale), inverseScale);
		}
	}
}
