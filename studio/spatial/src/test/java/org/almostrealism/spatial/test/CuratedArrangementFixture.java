/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.spatial.test;

import org.almostrealism.io.SystemUtils;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.arrange.MixdownManager;

import java.io.File;
import java.io.IOException;

/**
 * Shared setup for tests and harnesses that run arrangement generation over the
 * curated scene: the standard realtime mixdown configuration, and scene loading
 * from the curated library, pattern factory, and persisted settings.
 */
public class CuratedArrangementFixture {

	/**
	 * The curated sample library the scene draws from, overridable via
	 * {@code AR_RINGS_LIBRARY}. The default is the macOS shared-library path; CI runners that
	 * stage the library elsewhere set the property instead of relying on it.
	 */
	public static final String SAMPLES =
			SystemUtils.getProperty("AR_RINGS_LIBRARY", "/Users/Shared/Music/Samples");

	/**
	 * The curated pattern factory the scene draws from, overridable via
	 * {@code AR_RINGS_PATTERNS}. Defaults alongside {@link #SAMPLES}.
	 */
	public static final String PATTERN_FACTORY =
			SystemUtils.getProperty("AR_RINGS_PATTERNS", "/Users/Shared/Music/pattern-factory.json");

	/** Persisted scene settings, shared with the compose render tests. */
	public static final String SCENE_SETTINGS =
			"../compose/results/pdsl-cutover/scene-settings.json";

	/** Not instantiable; all members are static. */
	private CuratedArrangementFixture() { }

	/** Returns {@code true} when the curated library and pattern factory exist. */
	public static boolean curatedSceneAvailable() {
		return new File(SAMPLES).exists() && new File(PATTERN_FACTORY).exists();
	}

	/** Applies the standard realtime mixdown configuration for generation runs. */
	public static void enableRealtimeMixdown() {
		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = true;
		MixdownManager.enablePdslMixdown = true;
		PatternSystemManager.enableWarnings = false;
		AudioSceneRealtimeRunner.renderAheadSlots = 24;
	}

	/**
	 * Loads the curated scene with the persisted settings when present.
	 *
	 * @param totalMeasures total measures for the arrangement
	 * @return the loaded scene
	 * @throws IOException if the scene cannot be loaded
	 */
	public static AudioScene<?> loadCuratedScene(int totalMeasures) throws IOException {
		File settings = new File(SCENE_SETTINGS);
		AudioScene<?> scene = AudioScene.load(
				settings.exists() ? settings.getAbsolutePath() : null,
				new File(PATTERN_FACTORY).getAbsolutePath(),
				new File(SAMPLES).getAbsolutePath(), 120.0, 44100);
		scene.setTotalMeasures(totalMeasures);
		return scene;
	}
}
