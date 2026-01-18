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

// TODO  Move to common
public class Noise implements IntensityMap {
	private final double scaleU = 10.0;
	private final double scaleV = 10.0;
	private final double scaleW = 10.0;
	private final double[][] g;
	private final int[] p;
	
	public Noise() { this(256); }
	
	public Noise(int n) {
		this.p = new int[n];
		
		for (int i = 1; i < n; i++) {
			int j = (int) (Math.random() * n);
			while (this.p[j] > 0) j = (j + 1) % n;
			this.p[j] = i;
		}
		
		this.g = new double[n][3];
		
		for (int i = 0; i < n; i++) {
			double x = 1.0;
			double y = 1.0;
			double z = 1.0;
			
			while (x * x + y * y + z * z > 1) {
				x = Math.random() * 2.0 - 1.0;
				y = Math.random() * 2.0 - 1.0;
				z = Math.random() * 2.0 - 1.0;
			}
			
			this.g[i][0] = x;
			this.g[i][1] = y;
			this.g[i][2] = z;
		}
	}
	
	public double getIntensity(double u, double v, double w) {
		u = this.scaleU * u;
		v = this.scaleV * v;
		w = this.scaleW * w;
		double x = Math.floor(u);
		double y = Math.floor(v);
		double z = Math.floor(w);
		
		double n = 0;
		
		for (int i = 0; i <= 1; i++) {
			for (int j = 0; j <= 1; j++) {
				for (int k = 0; k <= 1; k++) {
					n = n + this.omega(x + i, y + j, z + k,
										u - x - i, v - y - j, w - z - k);
				}
			}
		}
		
		return n;
	}
	
	protected double omega(double i, double j, double k, double x, double y, double z) {
		return this.omega(x) * this.omega(y) * this.omega(z) *
					this.gamma((int) i, (int) j, (int) k, x, y, z);
	}
	
	protected double gamma(int i, int j, int k, double u, double v, double w) {
		double[] x = this.g[this.phi(i + this.phi(j + this.phi(k)))];
		return x[0] * u + x[1] * v + x[2] * w;
	}
	
	protected int phi(int t) { return this.p[t % this.p.length]; }
	
	protected double omega(double t) {
		t = Math.abs(t);
		
		if (t < 1) {
			return 2.0 * t * t * t - 3 * t * t + 1;
		} else {
			return 0.0;
		}
	}
}
