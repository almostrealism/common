/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.color;

/**
 * {@link RGBA} extends {@link RGB} to include an alpha channel.
 */
public class RGBA extends RGB {
	private double a;

	public RGBA() {
		super(0.0, 0.0,0.0);
		this.a = 0.0;
	}

	public RGBA(double r, double g, double b, double a) {
		super(r, g, b);
		this.a = a;
	}

	public RGBA(double... values) {
		this(values[0], values[1], values[2], values.length > 3 ? values[3] : 1.0);
	}

	public float r() { return (float) getRed(); }
	public float g() { return (float) getGreen(); }
	public float b() { return (float) getBlue(); }
	public float a() { return (float) getAlpha(); }

	public void setAlpha(double a) { this.a = a; }
	public double getAlpha() { return this.a; }

	public double[] toArray() { return new double[] { getRed(), getGreen(), getBlue(), getAlpha() }; }
}
