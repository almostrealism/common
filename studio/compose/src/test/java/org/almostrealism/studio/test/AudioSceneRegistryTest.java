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

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Tests that the static active-scene registry in {@link AudioScene} does not pin
 * scenes alive: a scene abandoned without {@link AudioScene#destroy()} must become
 * reclaimable by the garbage collector so the native memory it owns can be freed.
 */
public class AudioSceneRegistryTest extends TestSuiteBase {
	/** Upper bound on collection attempts before a scene is declared pinned. */
	private static final int MAX_GC_ATTEMPTS = 50;

	/**
	 * Builds a scene that needs no sample library.
	 *
	 * @return a new scene, registered in the active-scene registry
	 */
	private static AudioScene<?> createScene() {
		return new AudioScene<>(120.0, 2, 2, OutputLine.sampleRate);
	}

	/**
	 * Requests garbage collection until every given reference is cleared or the
	 * attempt limit is reached.
	 *
	 * @param refs the references expected to be cleared
	 */
	private static void collectUntilCleared(List<WeakReference<AudioScene<?>>> refs) {
		for (int i = 0; i < MAX_GC_ATTEMPTS && refs.stream().anyMatch(r -> r.get() != null); i++) {
			System.gc();

			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	/**
	 * A scene constructed and then abandoned without being destroyed is collected,
	 * proving the registry entry does not keep it strongly reachable.
	 */
	@Test(timeout = 120_000)
	public void abandonedSceneIsReclaimable() {
		List<WeakReference<AudioScene<?>>> refs = new ArrayList<>();
		refs.add(new WeakReference<>(createScene()));

		collectUntilCleared(refs);
		assertNull("Abandoned scene should not be pinned by the active registry",
				refs.get(0).get());
	}

	/**
	 * Several abandoned scenes are all collected, while a scene that is still held
	 * survives the same collections and can afterwards be destroyed normally (its
	 * deregistration purges the cleared entries of the collected scenes).
	 */
	@Test(timeout = 180_000)
	public void abandonedScenesAreReclaimedWhileLiveSceneSurvives() {
		AudioScene<?> live = createScene();

		try {
			List<WeakReference<AudioScene<?>>> refs = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				refs.add(new WeakReference<>(createScene()));
			}

			collectUntilCleared(refs);

			for (int i = 0; i < refs.size(); i++) {
				assertNull("Abandoned scene " + i + " should have been reclaimed",
						refs.get(i).get());
			}

			assertNotNull(live.getGenome());

			// Registering after collection purges the cleared entries; the new scene
			// is still live and is destroyed explicitly.
			AudioScene<?> next = createScene();
			next.destroy();
		} finally {
			live.destroy();
		}
	}
}
