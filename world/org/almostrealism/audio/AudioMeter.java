/*
 * Copyright 2020 Michael Murray
 *
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

import org.almostrealism.algebra.Scalar;
import org.almostrealism.graph.Receptor;
import org.almostrealism.util.Evaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class AudioMeter implements Receptor<Scalar> {
	private Receptor<Scalar> forwarding;
	private int freq = 1;
	private int count = 0;
	
	private long clipCount;
	private double clipMax = 1.0;
	private double clipMin = -1.0;
	
	private double silenceValue = 0;
	private long silenceDuration;
	
	private boolean outEnabled = false;

	private List<Consumer<Scalar>> listeners;
	
	public AudioMeter() {
		listeners = new ArrayList<>();
	}
	
	public void setTextOutputEnabled(boolean enabled) { this.outEnabled = enabled; }
	
	public void setReportingFrequency(int msec) {
		this.freq = (int) ((msec / 1000d) * OutputLine.sampleRate);
	}
	
	public void setForwarding(Receptor<Scalar> r) { this.forwarding = r; }
	
	public void setSilenceValue(double value) { this.silenceValue = value; }
	
	public long getSilenceDuration() { return this.silenceDuration; }
	
	public void setClipMaxValue(long value) { this.clipMax = value; }

	public void setClipMinValue(long value) { this.clipMin = value; }
	
	public long getClipCount() { return clipCount; }

	public void addListener(Consumer<Scalar> listener) {
		listeners.add(listener);
	}

	public void removeListener(Consumer<Scalar> listener) {
		listeners.remove(listener);
	}

	@Override
	public Supplier<Runnable> push(Evaluable<Scalar> protein) {
		Supplier<Runnable> f = forwarding == null ? null : forwarding.push(protein);

		return () -> () -> {
			Scalar p = protein.evaluate();

			if (outEnabled) {
				if (count == 0) {
					System.out.println(p);
				} else if (p.getValue() != 0) {
					System.out.println(p + " [Frame " + count + " of " + freq + "]");
				}
			}

			count++;
			count = count % freq;

			if (p.getValue() >= clipMax || p.getValue() <= clipMin) clipCount++;
			if (p.getValue() > silenceValue) silenceDuration = 0;
			if (p.getValue() <= silenceValue) silenceDuration++;

			if (f != null) f.get().run();

			listeners.forEach(listener -> listener.accept(p));
		};
	}
}
