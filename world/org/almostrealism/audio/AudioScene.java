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

package org.almostrealism.audio;

import org.almostrealism.space.ShadableSurface;
import org.almostrealism.time.Animation;
import org.almostrealism.time.AutomationData;
import org.almostrealism.uml.ModelEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ModelEntity
public class AudioScene<T extends ShadableSurface, S extends AudioSample> {
	private double bpm;

	private Animation<T> scene;
	private Map<T, S> samples;
	private Map<T, List<AutomationData>> automation;

	public AudioScene(Animation<T> scene) {
		this.bpm = 120.0;
		this.scene = scene;
		this.samples = new HashMap<>();
		this.automation = new HashMap<>();
	}

	public void setBPM(double bpm) { this.bpm = bpm; }
	public double getBPM() { return this.bpm; }

	public Animation<T> getScene() { return scene; }
	public Map<T, S> getSamples() { return samples; }
	public Map<T, List<AutomationData>> getAutomation() { return automation; }
}
