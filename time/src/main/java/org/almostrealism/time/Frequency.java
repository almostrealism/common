/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.time;

public class Frequency {
	private final double hertz;

	public Frequency(double hertz) {
		this.hertz = hertz;
	}

	public double asHertz() { return hertz; }

	public double getWaveLength() { return 1.0 / asHertz(); }

	public double l(int count) { return count * getWaveLength(); }

	public static Frequency forBPM(double bpm) {
		return new Frequency(bpm / 60);
	}
}
