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

package org.almostrealism.studio.pattern.test;

import org.almostrealism.audio.WaveOutput;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneLoader;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Verifies that tearing down or reloading an {@link AudioScene} while a real-time runner's
 * render-ahead producer is running cannot let that producer render against the pattern
 * state being released.
 *
 * <p>The PDSL runner renders patterns ahead of playback on a dedicated producer thread
 * ({@code pattern-render-ahead}). {@link AudioScene#destroy()} frees the pattern caches and
 * render buffers that thread reads, and a settings reload destroys the pattern managers it
 * renders from. Both must stop the producer — waiting for an in-flight render — before they
 * release anything. Each test observes the outcome from the stale runner: its stream must be
 * stopped (a later tick is rejected as "not running"), not failed. A producer left running
 * would instead keep serving ticks, or fail with a render error after rendering a torn-down
 * manager.</p>
 */
public class RenderAheadLifecycleTest extends AudioSceneTestBase {

	/** Frames per buffer. */
	private static final int BUFFER_SIZE = 1024;

	/** Name of the render-ahead producer thread. */
	private static final String PRODUCER_THREAD = "pattern-render-ahead";

	/** The {@link MixdownManager#enablePdslMixdown} value to restore. */
	private boolean previousPdsl;

	/** The {@link AudioSceneRealtimeRunner#renderAheadSlots} value to restore. */
	private int previousSlots;

	/** Routes runner builds to the PDSL path, the one with a render-ahead producer. */
	@Before
	public void enableRenderAhead() {
		previousPdsl = MixdownManager.enablePdslMixdown;
		previousSlots = AudioSceneRealtimeRunner.renderAheadSlots;
		MixdownManager.enablePdslMixdown = true;
		AudioSceneRealtimeRunner.renderAheadSlots = 4;
	}

	/** Restores the runner configuration. */
	@After
	public void restoreRenderAhead() {
		MixdownManager.enablePdslMixdown = previousPdsl;
		AudioSceneRealtimeRunner.renderAheadSlots = previousSlots;
	}

	/**
	 * {@link AudioScene#destroy()} stops the render-ahead producer before it releases the
	 * pattern state, so the producer exits cleanly instead of rendering freed memory.
	 */
	@Test(timeout = 600_000)
	public void destroyStopsRenderAheadBeforeReleasingPatterns() {
		assertStopsRenderAhead("destroy", AudioScene::destroy);
	}

	/**
	 * Reloading settings into a scene stops the render-ahead producer before the reload
	 * destroys the pattern managers it renders from.
	 */
	@Test(timeout = 600_000)
	public void settingsReloadStopsRenderAheadBeforeReplacingPatterns() {
		assertStopsRenderAhead("settings reload", scene -> {
			AudioSceneLoader.Settings settings = scene.getSettings();
			scene.setSettings(settings, root -> scene.getLibrary(), null);
		});
	}

	/**
	 * Builds a PDSL runner on a scene with a note-bearing genome, starts its render-ahead
	 * producer and plays a few buffers, then applies {@code teardown} and asserts that the
	 * producer has exited and that the runner's stream reports itself stopped rather than
	 * failed or still serving buffers.
	 *
	 * @param label    names the teardown, for failure messages
	 * @param teardown the scene operation that releases the state the producer renders from
	 */
	private void assertStopsRenderAhead(String label, Consumer<AudioScene<?>> teardown) {
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), 2);
		long seed = findWorkingGenomeSeed(scene, getSamplesDir());
		Assert.assertTrue("No working genome seed found", seed >= 0);
		applyGenome(scene, seed);

		Set<Thread> existing = producerThreads();
		WaveOutput out = new WaveOutput(() -> new File("results/render-ahead-lifecycle.wav"), 24, true);
		TemporalCellular runner = scene.runnerRealTime(new MultiChannelAudioOutput(out), null, BUFFER_SIZE);

		try (out) {
			runner.setup().get().run();
			Runnable tick = runner.tick().get();
			for (int i = 0; i < 3; i++) {
				tick.run();
			}

			Set<Thread> started = producerThreads();
			started.removeAll(existing);
			Assert.assertEquals("the runner should have started one render-ahead producer",
					1, started.size());
			Thread producer = started.iterator().next();

			teardown.accept(scene);

			Assert.assertFalse("the " + label + " must stop the render-ahead producer",
					producer.isAlive());

			try {
				tick.run();
				Assert.fail("a runner whose scene was torn down must not keep playing after the "
						+ label);
			} catch (RuntimeException expected) {
				Assert.assertTrue("the stale runner should report a stopped stream, not a render"
								+ " failure: " + expected,
						causeMessageContains(expected, "not running"));
			}
		} finally {
			runner.destroy();
		}
	}

	/**
	 * Returns the live render-ahead producer threads.
	 *
	 * @return the producer threads currently alive
	 */
	private Set<Thread> producerThreads() {
		Set<Thread> producers = new HashSet<>();
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (PRODUCER_THREAD.equals(thread.getName()) && thread.isAlive()) {
				producers.add(thread);
			}
		}
		return producers;
	}

	/**
	 * Returns whether the throwable or any of its causes has a message containing the text.
	 *
	 * @param t    the throwable to inspect
	 * @param text the text to look for
	 * @return {@code true} if a message in the cause chain contains {@code text}
	 */
	private boolean causeMessageContains(Throwable t, String text) {
		for (Throwable cursor = t; cursor != null; cursor = cursor.getCause()) {
			if (cursor.getMessage() != null && cursor.getMessage().contains(text)) return true;
		}
		return false;
	}
}
