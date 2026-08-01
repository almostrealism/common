/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.test;

import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.MockOutputLine;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.studio.BufferedAudioPlayer;
import org.almostrealism.studio.Mixer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Integration tests for multi-device playback, verifying that the
 * full pipeline from {@link BufferedAudioPlayer} through {@link Mixer}
 * output groups routes audio to separate {@link MockOutputLine} instances
 * with single-pass channel rendering.
 */
public class MultiDevicePlaybackTest extends TestSuiteBase
		implements CellFeatures, AudioTestFeatures {

	/**
	 * Verifies that deliverGroups() creates independent schedulers that
	 * each produce audio output on their respective mock output lines.
	 */
	@Test(timeout = 30_000)
	public void deliverGroupsRoutesToSeparateOutputs() {
		String testAudio = getTestWavPath();

		BufferedAudioPlayer player = new BufferedAudioPlayer(
				4, OutputLine.sampleRate, OutputLine.sampleRate * 10);

		// Configure output groups: channels 0,1 → device A; channels 2,3 → device B
		Mixer mixer = player.getMixer().getChannelMixer();
		mixer.addOutputGroup("deviceA", 0, 1);
		mixer.addOutputGroup("deviceB", 2, 3);
		mixer.applyOutputGroups();

		MockOutputLine deviceA = new MockOutputLine();
		MockOutputLine deviceB = new MockOutputLine();

		Map<String, OutputLine> groupLines = new LinkedHashMap<>();
		groupLines.put("deviceA", deviceA);
		groupLines.put("deviceB", deviceB);

		Map<String, BufferedOutputScheduler> schedulers = player.deliverGroups(groupLines);

		Assert.assertEquals("Should have two schedulers", 2, schedulers.size());
		Assert.assertNotNull(schedulers.get("deviceA"));
		Assert.assertNotNull(schedulers.get("deviceB"));

		// Load audio into channels 0 and 2 (one per group)
		player.load(0, testAudio);
		player.load(2, testAudio);

		// Start all schedulers
		for (BufferedOutputScheduler s : schedulers.values()) {
			s.start();
		}

		try {
			Thread.sleep(4000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Both devices should have received audio
		Assert.assertTrue("Device A should have received audio",
				deviceA.getFramesWritten() > 0);
		Assert.assertTrue("Device B should have received audio",
				deviceB.getFramesWritten() > 0);

		// Stop schedulers
		for (BufferedOutputScheduler s : schedulers.values()) {
			s.stop();
		}
	}

	/**
	 * Verifies that when only one group has loaded audio, the other
	 * group's output still runs (producing silence) without errors.
	 */
	@Test(timeout = 30_000)
	public void partialLoadDoesNotFail() {
		String testAudio = getTestWavPath();

		BufferedAudioPlayer player = new BufferedAudioPlayer(
				4, OutputLine.sampleRate, OutputLine.sampleRate * 10);

		Mixer mixer = player.getMixer().getChannelMixer();
		mixer.addOutputGroup("speakers", 0, 1);
		mixer.addOutputGroup("headphones", 2, 3);
		mixer.applyOutputGroups();

		MockOutputLine speakers = new MockOutputLine();
		MockOutputLine headphones = new MockOutputLine();

		Map<String, OutputLine> groupLines = new LinkedHashMap<>();
		groupLines.put("speakers", speakers);
		groupLines.put("headphones", headphones);

		Map<String, BufferedOutputScheduler> schedulers = player.deliverGroups(groupLines);

		// Only load audio on channel 0 (speakers group)
		player.load(0, testAudio);

		for (BufferedOutputScheduler s : schedulers.values()) {
			s.start();
		}

		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Both devices should have written frames (headphones writes silence)
		Assert.assertTrue("Speakers should have received audio",
				speakers.getFramesWritten() > 0);
		Assert.assertTrue("Headphones scheduler should still be running",
				headphones.getFramesWritten() > 0);

		for (BufferedOutputScheduler s : schedulers.values()) {
			s.stop();
		}
	}

	/**
	 * Verifies backward compatibility: when no output groups are configured,
	 * the single-device deliver() path still works.
	 */
	@Test(timeout = 30_000)
	public void singleDevicePathUnchanged() {
		String testAudio = getTestWavPath();

		BufferedAudioPlayer player = new BufferedAudioPlayer(
				2, OutputLine.sampleRate, OutputLine.sampleRate * 10);

		MockOutputLine out = new MockOutputLine();
		BufferedOutputScheduler scheduler = player.deliver(out);
		player.load(0, testAudio);

		scheduler.start();

		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		Assert.assertTrue("Single-device output should have received audio",
				out.getFramesWritten() > 0);

		scheduler.stop();
	}
}
