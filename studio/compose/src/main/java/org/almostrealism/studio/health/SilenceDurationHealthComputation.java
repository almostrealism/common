/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.studio.health;

import org.almostrealism.studio.AudioMeter;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Health computation that evaluates audio quality by monitoring silence duration.
 * The score reflects what fraction of the standard evaluation duration the audio
 * remained audible — if silence persists beyond the configured maximum, evaluation
 * ends early and a partial score is returned.
 */
public class SilenceDurationHealthComputation extends HealthComputationAdapter implements ConsoleFeatures {
	/** When {@code true}, prints diagnostic frame counts at the end of evaluation. */
	public static boolean enableVerbose = false;

	/** When {@code true}, silence detection is active during evaluation. */
	public static boolean enableSilenceCheck = false;

	/** Maximum allowed consecutive silence in audio frames before early exit. */
	private int maxSilence;

	/** Minimum amplitude below which audio is considered silent. */
	private final double silenceValue = 0.001;

	/** Maximum evaluation duration in frames (mirrors {@code standardDurationFrames}). */
	private final long max = standardDurationFrames;

	/** Listeners notified when excessive silence is detected. */
	private final List<Runnable> silenceListeners;

	/**
	 * Creates a mono silence-duration computation with a 2-second silence limit.
	 *
	 * @param channels the number of pattern channels
	 */
	public SilenceDurationHealthComputation(int channels) {
		this(channels, false, 2);
	}

	/**
	 * Creates a silence-duration computation with a configurable silence limit.
	 *
	 * @param channels        the number of pattern channels
	 * @param stereo          {@code true} for stereo output
	 * @param maxSilenceSec   maximum permissible silence in seconds
	 */
	public SilenceDurationHealthComputation(int channels, boolean stereo, int maxSilenceSec) {
		super(channels, stereo);
		setMaxSilence(maxSilenceSec);
		silenceListeners = new ArrayList<>();
	}
	
	/**
	 * Sets the maximum permissible consecutive silence duration.
	 *
	 * @param sec maximum silence duration in seconds
	 */
	public void setMaxSilence(int sec) { this.maxSilence = sec * OutputLine.sampleRate; }

	/**
	 * Sets the standard evaluation duration globally.
	 *
	 * @param sec standard evaluation duration in seconds
	 */
	public static void setStandardDuration(int sec) {
		standardDurationFrames = sec * OutputLine.sampleRate;
	}

	/**
	 * Adds a listener that is notified whenever excessive silence is detected.
	 *
	 * @param listener the listener to add
	 */
	public void addSilenceListener(Runnable listener) { silenceListeners.add(listener); }

	/** {@inheritDoc} */
	@Override
	protected void configureMeasures(Map<ChannelInfo, AudioMeter> measures) {
		measures.values().forEach(m -> m.setSilenceValue(silenceValue));
	}

	/**
	 * Checks whether the given meter has been silent for longer than the allowed maximum.
	 * Notifies silence listeners and returns {@code true} if silence is exceeded.
	 *
	 * @param meter the audio meter to interrogate
	 * @return {@code true} if excessive silence was detected
	 */
	public boolean checkForSilence(AudioMeter meter) {
		if (enableSilenceCheck) {
			if (meter.getSilenceDuration() > maxSilence) {
				silenceListeners.forEach(Runnable::run);
				return true;
			}
		}

		return false;
	}

	@Override
	public AudioHealthScore computeHealth() {
		long l;

		// Runnable push = organ.push(null).get();
		Runnable tick = getTarget().tick().get();

		for (l = 0; l < max; l++) {
			// push.run();

			for (AudioMeter m : getMeasures().values()) {
				// If silence occurs for too long, report the health score
				if (checkForSilence(m)) {
					return new AudioHealthScore(l, (double) l / standardDurationFrames);
				}
			}

			tick.run();
		}
		
		// Report the health score as an inverse
		// percentage of the expected duration
		if (enableVerbose)
			log(l + " frames of survival");
		
		// If no silence which was too long in duration
		// has occurred, return a perfect health score.
		return new AudioHealthScore(l, 1.0);
	}
}
