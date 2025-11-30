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

// TODO  Move to rings
public class Turbulence implements IntensityMap {
	private final Noise noise;
	private int itr = 8;

	public Turbulence() { this(new Noise(), 8); }
	
	public Turbulence(Noise noise, int itr) { this.noise = noise; this.itr = itr; }
	
	public double getIntensity(double u, double v, double w) {
		double n = 0.0;
		
		for (int i = 0; i < this.itr; i++) {
			double m = Math.pow(2.0, i);
			n = n + this.noise.getIntensity(m * u, m * v, m * w) / m;
		}
		
		return n;
	}
}
