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

import org.almostrealism.audio.AudioProteinCache;
import org.almostrealism.graph.ProteinCache;
import org.almostrealism.graph.Receptor;

public class AudioMeter implements Receptor<Long> {
	private ProteinCache<Long> cache;
	private Receptor<Long> forwarding;
	private int freq = 1;
	private int count = 0;
	
	private long clipCount;
	private long clipMax = Long.MAX_VALUE / 2;
	private long clipMin = Long.MIN_VALUE / 2;
	
	private long silenceValue = 0;
	private long silenceDuration;
	
	private boolean outEnabled = false;
	
	public AudioMeter(ProteinCache<Long> cache) { setProteinCache(cache); }
	
	public void setTextOutputEnabled(boolean enabled) { this.outEnabled = enabled; }
	
	public void setReportingFrequency(int msec) {
		this.freq = (int) ((msec / 1000d) * AudioProteinCache.sampleRate);
	}
	
	public void setProteinCache(ProteinCache<Long> p) { this.cache = p; }
	
	public ProteinCache<Long> getProteinCache() { return this.cache; }
	
	public void setForwarding(Receptor<Long> r) { this.forwarding = r; }
	
	public void setSilenceValue(long value) { this.silenceValue = value; }
	
	public long getSilenceDuration() { return this.silenceDuration; }
	
	public void setClipMaxValue(long value) { this.clipMax = value; }

	public void setClipMinValue(long value) { this.clipMin = value; }
	
	public long getClipCount() { return clipCount; }
	
	public void push(long l) {
		if (outEnabled) {
			if (count == 0) {
				System.out.println(cache.getProtein(l));
			} else if (cache.getProtein(l) != 0) {
				System.out.println(cache.getProtein(l) + " [Frame " + count + " of " + freq + "]");
			}
		}
		
		count++;
		count = count % freq;
		
		if (cache.getProtein(l) >= clipMax || cache.getProtein(l) <= clipMin) clipCount++;
		if (cache.getProtein(l) > silenceValue) silenceDuration = 0;
		if (cache.getProtein(l) <= silenceValue) silenceDuration++;
		
		if (forwarding != null) forwarding.push(l);
	}
}
