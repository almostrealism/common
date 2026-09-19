/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.studio.test;

import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests the routing of stereo channels to the {@link WaveOutput}s behind a
 * {@link MultiChannelAudioOutput}, in particular that a mono destination
 * exposes no receptor for the channel it does not have.
 */
public class MultiChannelAudioOutputTest extends TestSuiteBase {
	/** Timeline length of the outputs under test; small, since nothing is rendered. */
	private static final int FRAMES = 1024;

	/**
	 * Creates a file-less output with the given channel layout.
	 *
	 * @param stereo {@code true} for two channels, {@code false} for one
	 * @return the output
	 */
	private WaveOutput output(boolean stereo) {
		return new WaveOutput(() -> null, 24, OutputLine.sampleRate, FRAMES, stereo);
	}

	/**
	 * A mono master has a receptor for the left channel only.
	 */
	@Test(timeout = 60000)
	public void monoMasterHasLeftReceptorOnly() {
		try (WaveOutput master = output(false)) {
			MultiChannelAudioOutput out = new MultiChannelAudioOutput(master);
			Assert.assertNotNull(out.getMaster(ChannelInfo.StereoChannel.LEFT));
			Assert.assertNull(out.getMaster(ChannelInfo.StereoChannel.RIGHT));
		}
	}

	/**
	 * A stereo master has a receptor for both channels.
	 */
	@Test(timeout = 60000)
	public void stereoMasterHasBothReceptors() {
		try (WaveOutput master = output(true)) {
			MultiChannelAudioOutput out = new MultiChannelAudioOutput(master);
			Assert.assertNotNull(out.getMaster(ChannelInfo.StereoChannel.LEFT));
			Assert.assertNotNull(out.getMaster(ChannelInfo.StereoChannel.RIGHT));
		}
	}

	/**
	 * Mono stems follow the same rule as the master: the right channel of a
	 * pattern channel's stem is absent rather than an error.
	 */
	@Test(timeout = 60000)
	public void monoStemsHaveLeftReceptorOnly() {
		try (WaveOutput master = output(false); WaveOutput stem = output(false)) {
			MultiChannelAudioOutput out = new MultiChannelAudioOutput(master, List.of(stem));
			Assert.assertNotNull(out.getStem(0, ChannelInfo.StereoChannel.LEFT));
			Assert.assertNull(out.getStem(0, ChannelInfo.StereoChannel.RIGHT));
		}
	}

	/**
	 * When measure monitoring is disabled, {@link MultiChannelAudioOutput#getMeasures}
	 * must return an empty list rather than dereferencing the absent backing map, so
	 * callers that build a destination list (e.g. {@code MixdownManager.createEfx}'s
	 * {@code disableClean} branch) don't fail before reaching the null-filtering
	 * Receptor factories.
	 */
	@Test(timeout = 60000)
	public void measuresInactiveReturnsEmptyList() {
		try (WaveOutput master = output(false)) {
			MultiChannelAudioOutput out = new MultiChannelAudioOutput(master);
			Assert.assertFalse(out.isMeasuresActive());
			Assert.assertTrue(out.getMeasures(ChannelInfo.StereoChannel.LEFT).isEmpty());
		}
	}
}
