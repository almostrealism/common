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

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneLoader;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * Verifies that per-channel stereo pan is persisted by {@link AudioSceneLoader.Settings}:
 * {@link AudioSceneLoader.Settings#from(AudioScene)} captures every channel's pan, the
 * value survives a JSON round trip, and
 * {@link AudioSceneLoader.Settings#applyTo} restores it. A settings file written before
 * pan existed (no {@code pan} entry), or one listing fewer channels than the scene has,
 * must leave the unlisted channels centred rather than keeping a stale placement.
 */
public class AudioSceneLoaderPanSettingsTest extends TestSuiteBase {
	/** Channels in the scenes under test. */
	private static final int CHANNELS = 2;

	/**
	 * Builds a scene that needs no sample library, initialised with the default settings
	 * (as a scene loading with no settings file is), so every manager it snapshots has
	 * state to report.
	 *
	 * @return a new scene with {@link #CHANNELS} channels
	 */
	private static AudioScene<?> createScene() {
		AudioScene<?> scene = new AudioScene<>(120.0, CHANNELS, CHANNELS, OutputLine.sampleRate);
		scene.setSettings(AudioSceneLoader.Settings.defaultSettings(CHANNELS,
				AudioScene.DEFAULT_PATTERNS_PER_CHANNEL,
				AudioScene.DEFAULT_ACTIVE_PATTERNS,
				AudioScene.DEFAULT_LAYERS,
				AudioScene.DEFAULT_LAYER_SCALE,
				AudioScene.DEFAULT_DURATION), root -> null, null);
		return scene;
	}

	/**
	 * Pan set on one scene is captured in its settings, survives serialization to JSON and
	 * back, and is restored on a second scene.
	 *
	 * @throws IOException if the settings cannot be serialized
	 */
	@Test(timeout = 120_000)
	public void panRoundTripsThroughSettings() throws IOException {
		AudioScene<?> source = createScene();
		AudioScene<?> target = createScene();

		try {
			source.getMixdownManager().setPan(0, -0.5);
			source.getMixdownManager().setPan(1, 1.0);

			AudioSceneLoader.Settings settings = AudioSceneLoader.Settings.from(source);
			Assert.assertEquals(List.of(-0.5, 1.0), settings.getPan());

			String json = AudioSceneLoader.defaultMapper().writeValueAsString(settings);
			AudioSceneLoader.Settings restored = AudioSceneLoader.defaultMapper()
					.readValue(json, AudioSceneLoader.Settings.class);
			Assert.assertEquals(List.of(-0.5, 1.0), restored.getPan());

			restored.applyTo(target, root -> null, null);
			MixdownManager mixdown = target.getMixdownManager();
			Assert.assertEquals(-0.5, mixdown.getPan(0), 1e-9);
			Assert.assertEquals(1.0, mixdown.getPan(1), 1e-9);
		} finally {
			source.destroy();
			target.destroy();
		}
	}

	/**
	 * Settings with no pan entry (a file saved before pan existed) centre every channel,
	 * and a pan list shorter than the channel count centres the channels it omits — even
	 * when the scene was previously panned, so no stale placement survives a load.
	 */
	@Test(timeout = 120_000)
	public void missingOrShortPanCentresChannels() {
		AudioScene<?> legacy = createScene();
		AudioScene<?> partial = createScene();

		try {
			MixdownManager legacyMixdown = legacy.getMixdownManager();
			AudioSceneLoader.Settings settings = AudioSceneLoader.Settings.from(legacy);

			legacyMixdown.setPan(0, 0.7);
			legacyMixdown.setPan(1, -0.7);
			settings.setPan(null);
			settings.applyTo(legacy, root -> null, null);
			Assert.assertEquals("a missing pan entry must centre channel 0",
					0.0, legacyMixdown.getPan(0), 1e-9);
			Assert.assertEquals("a missing pan entry must centre channel 1",
					0.0, legacyMixdown.getPan(1), 1e-9);

			MixdownManager partialMixdown = partial.getMixdownManager();
			partialMixdown.setPan(1, -0.7);
			settings.setPan(List.of(-0.25));
			settings.applyTo(partial, root -> null, null);
			Assert.assertEquals("a listed channel takes its pan", -0.25, partialMixdown.getPan(0), 1e-9);
			Assert.assertEquals("an unlisted channel must be centred", 0.0, partialMixdown.getPan(1), 1e-9);
		} finally {
			legacy.destroy();
			partial.destroy();
		}
	}
}
