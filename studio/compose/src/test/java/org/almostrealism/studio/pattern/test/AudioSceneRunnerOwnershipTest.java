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

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the ownership contract between an {@link AudioScene} and the real-time runners
 * built by {@link AudioScene#runnerRealTime}: a runner the caller never destroys is
 * destroyed — its render-ahead producer thread stopped and joined — when the scene is
 * destroyed, and a runner's {@code destroy()} is idempotent, so a runner released by its
 * caller is not released a second time by the scene.
 */
public class AudioSceneRunnerOwnershipTest extends AudioSceneTestBase {

	/** Name {@code PatternRenderStream} gives its producer thread. */
	private static final String PRODUCER_THREAD = "pattern-render-ahead";

	/** Channels in the test scene; two keeps the runner build fast and PDSL-supported. */
	private static final int SOURCE_COUNT = 2;

	/** Frames per buffer for the runners built here. */
	private static final int BUFFER_SIZE = 1024;

	/**
	 * Builds a PDSL runner, starts its producer thread through {@code setup()}, never
	 * destroys the runner, and then destroys the scene. The producer thread must be gone
	 * once {@link AudioScene#destroy()} returns: the scene destroys the runner before it
	 * frees the render cells and consolidated buffer that thread renders into. Before the
	 * scene owned its runners, the thread stayed alive (blocked on, or still rendering
	 * into, freed buffers) after the scene was destroyed.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void sceneDestroyStopsUndestroyedRunner() {
		boolean pdsl = MixdownManager.enablePdslMixdown;
		MixdownManager.enablePdslMixdown = true;
		List<WaveOutput> outputs = new ArrayList<>();
		Runnable setup = null;

		try {
			AudioScene<?> scene = createBaselineScene(getSamplesDir(), SOURCE_COUNT);
			applyGenome(scene, 1);
			int before = producerThreadCount();

			TemporalCellular runner = scene.runnerRealTime(output("ownership-scene", outputs), BUFFER_SIZE);
			setup = runner.setup().get();
			setup.run();
			assertEquals("setup() should start exactly one producer thread",
					before + 1, producerThreadCount());

			scene.destroy();
			assertEquals("scene.destroy() should stop the runner's producer thread",
					before, producerThreadCount());

			// The scene already released the runner; a caller destroying it afterwards
			// must be a no-op rather than a second release of freed resources.
			((Destroyable) runner).destroy();
			assertEquals(before, producerThreadCount());
		} finally {
			// setup().get() compiles native kernels the runner does not retain; release them
			// before the outputs they wrote into (the scene already stopped the producer).
			Destroyable.destroy(setup);
			Destroyable.destroy(outputs);
			MixdownManager.enablePdslMixdown = pdsl;
		}
	}

	/**
	 * Tracks live runners across both DSP paths: each build adds one, a caller's
	 * {@code destroy()} removes exactly that one, a repeated {@code destroy()} changes
	 * nothing, and {@link AudioSceneRealtimeRunner#destroy()} releases every runner still
	 * live, after which destroying the collaborator again is also a no-op.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void liveRunnersAreTrackedUntilDestroyed() {
		boolean pdsl = MixdownManager.enablePdslMixdown;
		List<WaveOutput> outputs = new ArrayList<>();

		try {
			AudioScene<?> scene = createBaselineScene(getSamplesDir(), SOURCE_COUNT);
			applyGenome(scene, 1);
			AudioSceneRealtimeRunner runners = new AudioSceneRealtimeRunner(scene);
			assertEquals(0, runners.getLiveRunnerCount());

			MixdownManager.enablePdslMixdown = true;
			TemporalCellular pdslRunner = runners.create(output("ownership-pdsl", outputs), null, BUFFER_SIZE);
			assertEquals(1, runners.getLiveRunnerCount());

			MixdownManager.enablePdslMixdown = false;
			TemporalCellular cellListRunner = runners.create(output("ownership-celllist", outputs),
					List.of(0), BUFFER_SIZE);
			assertEquals(2, runners.getLiveRunnerCount());

			((Destroyable) cellListRunner).destroy();
			assertEquals(1, runners.getLiveRunnerCount());
			((Destroyable) cellListRunner).destroy();
			assertEquals("a repeated destroy() must not untrack another runner",
					1, runners.getLiveRunnerCount());

			runners.destroy();
			assertEquals(0, runners.getLiveRunnerCount());
			runners.destroy();
			((Destroyable) pdslRunner).destroy();
			assertEquals(0, runners.getLiveRunnerCount());
		} finally {
			Destroyable.destroy(outputs);
			MixdownManager.enablePdslMixdown = pdsl;
		}
	}

	/**
	 * Returns a fresh output writing to a WAV file under {@code results/}. The underlying
	 * {@link WaveOutput} self-allocates a full timeline buffer (hundreds of MB) that no runner
	 * or scene owns, so it is registered in {@code cleanup} for the caller to destroy in a
	 * {@code finally}; otherwise it leaks into the shared test JVM — the out-of-memory mode
	 * this branch exists to close.
	 *
	 * @param name    file name stem
	 * @param cleanup collection the created {@link WaveOutput} is added to for later release
	 * @return the output
	 */
	private static MultiChannelAudioOutput output(String name, List<WaveOutput> cleanup) {
		WaveOutput out = new WaveOutput(() -> new File("results/" + name + ".wav"), 24, true);
		cleanup.add(out);
		return new MultiChannelAudioOutput(out);
	}

	/**
	 * Counts live render-ahead producer threads in this JVM.
	 *
	 * @return the number of alive threads named {@link #PRODUCER_THREAD}
	 */
	private static int producerThreadCount() {
		return (int) Thread.getAllStackTraces().keySet().stream()
				.filter(t -> t.isAlive() && PRODUCER_THREAD.equals(t.getName()))
				.count();
	}
}
