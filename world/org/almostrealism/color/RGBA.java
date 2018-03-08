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

public class RGBA {
	private RGB rgb;
	public double a;

	public RGBA() {
		this.rgb = new RGB(0.0, 0.0,0.0);
		this.a = 0.0f;
	}

	public RGBA(double r, double g, double b, double a) {
		this.rgb = new RGB(r, g, b);
		this.a = a;
	}

	public RGB getRGB() { return rgb; }

	public float r() { return (float) rgb.getRed(); }
	public float g() { return (float) rgb.getGreen(); }
	public float b() { return (float) rgb.getBlue(); }
}
