/*
 * Copyright 2016 Michael Murray
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

package org.almostrealism.texture;

public class CosineIntensityMap implements IntensityMap {
	private final double alpha;
	private final double beta;
	private final double tau;
	private IntensityMap map;
	
	public CosineIntensityMap() {
		this(null);
	}
	
	public CosineIntensityMap(IntensityMap map) {
		this(3.5, 2.5, 2.0, map);
	}
	
	public CosineIntensityMap(double alpha, double beta, double tau, IntensityMap map) {
		this.alpha = alpha;
		this.beta = beta;
		this.tau = tau;
		this.map = map;
	}
	
	public void setIntensityMap(IntensityMap map) { this.map = map; }
	public IntensityMap getIntensityMap() { return this.map; }
	
	public double getIntensity(double u, double v, double w) {
		double z = this.map.getIntensity(this.tau * u, this.tau * v, this.tau * w);
		double t = 1 + Math.cos(Math.min(this.alpha * v + this.beta * z, 2.0 * Math.PI));
		return t / 2.0;
	}
}
