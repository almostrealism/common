/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.audio.data;

import java.util.function.DoubleSupplier;
import java.util.function.Function;

public class ParameterFunction implements Function<ParameterSet, Double> {
	private double x, y, z, c;

	public ParameterFunction() { }

	public ParameterFunction(double x, double y, double z, double c) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.c = c;
	}

	@Override
	public Double apply(ParameterSet params) {
		return Math.sin(2 * Math.PI * (params.getX() * getX() + params.getY() * getY() + params.getZ() * getZ() + getC()));
	}

	public Function<ParameterSet, Double> positive() {
		// TODO  Should this wrap instead of being continuous?
		return (ParameterSet params) -> Math.abs(apply(params));
	}

	public Function<ParameterSet, Double> power(double base, int unit, int offset) {
		return (ParameterSet params) -> {
			double selection = apply(params);
			if (selection > 0.0) selection = Math.floor(unit * selection);
			if (selection < 0.0) selection = Math.ceil(unit * selection);
			return Math.pow(base, selection + offset);
		};
	}

	public double getX() { return x; }
	public void setX(double x) { this.x = x; }

	public double getY() { return y; }
	public void setY(double y) { this.y = y; }

	public double getZ() { return z; }
	public void setZ(double z) { this.z = z; }

	public double getC() { return c; }
	public void setC(double c) { this.c = c; }

	public static ParameterFunction random() {
		return random(2.0);
	}

	public static ParameterFunction random(double scale) {
		DoubleSupplier rand = () -> (Math.random() - 0.5) * 2.0 * scale;
		return new ParameterFunction(rand.getAsDouble(), rand.getAsDouble(), rand.getAsDouble(), rand.getAsDouble());
	}
}
