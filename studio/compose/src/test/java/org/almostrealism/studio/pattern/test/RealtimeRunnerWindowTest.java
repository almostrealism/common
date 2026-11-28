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
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.RealtimePositionWindow;
import org.almostrealism.studio.RealtimeRunner;
import org.almostrealism.studio.RealtimeRunnerOptions;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

/**
 * Verifies the position window and control handle that
 * {@link AudioScene#runnerRealTime(MultiChannelAudioOutput, List, int, RealtimeRunnerOptions)}
 * adds to the PDSL real-time runner: a per-runner ring depth, an automation clock that wraps
 * with the loop, a retarget that moves the position without a mixdown reset, and a
 * {@code null}-options runner that keeps today's free-running frame sequence.
 */
public class RealtimeRunnerWindowTest extends AudioSceneTestBase {

	/** Frames per buffer. */
	private static final int BUFFER_SIZE = 1024;

	/** Seed of the genome assigned before rendering; rendering needs an assigned progression. */
	private static final long GENOME_SEED = 1L;

	/** The {@link MixdownManager#enablePdslMixdown} value to restore. */
	private boolean previousPdsl;

	/** Routes runner builds to the PDSL path, the one with a render-ahead ring. */
	@Before
	public void enablePdsl() {
		previousPdsl = MixdownManager.enablePdslMixdown;
		MixdownManager.enablePdslMixdown = true;
	}

	/** Restores the runner configuration. */
	@After
	public void restorePdsl() {
		MixdownManager.enablePdslMixdown = previousPdsl;
	}

	/**
	 * A runner built without options keeps the global ring depth, a non-moving window, and the
	 * free-running clock: after {@code n} ticks the clock is at the arrangement position of
	 * frame {@code n * bufferSize}, exactly as before the window existed.
	 */
	@Test(timeout = 600_000)
	public void nullOptionsKeepFreeRunningFrameSequence() {
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), 2);
		applyGenome(scene, GENOME_SEED);
		WaveOutput out = new WaveOutput(() -> new File("results/runner-window-null.wav"), 24, true);
		RealtimeRunner runner = scene.runnerRealTime(new MultiChannelAudioOutput(out),
				List.of(0), BUFFER_SIZE, null);

		try (out) {
			Assert.assertEquals(AudioSceneRealtimeRunner.renderAheadSlots, runner.renderAheadSlots());
			Assert.assertFalse(runner.getWindow().isLooping());

			runner.setup().get().run();
			Runnable tick = runner.tick().get();
			for (int i = 1; i <= 3; i++) {
				tick.run();
				Assert.assertEquals("tick " + i,
						scene.getTimeManager().positionForFrame((long) i * BUFFER_SIZE),
						scene.getTimeManager().getClock().getFrame(), 0.0);
			}
		} finally {
			runner.destroy();
		}
	}

	/**
	 * With a loop window the automation clock at the start of every tick is the pattern
	 * position of the buffer being mixed, so it wraps at the loop length; the per-runner depth
	 * override is honoured without touching the global; and a retarget moves the next buffer to
	 * the requested position with a ready slot and no exception.
	 */
	@Test(timeout = 600_000)
	public void windowWrapsClockAndRetargetMovesPosition() {
		int globalSlots = AudioSceneRealtimeRunner.renderAheadSlots;
		double loopFrames = BUFFER_SIZE * 2.5;

		AudioScene<?> scene = createBaselineScene(getSamplesDir(), 2);
		applyGenome(scene, GENOME_SEED);
		WaveOutput out = new WaveOutput(() -> new File("results/runner-window-loop.wav"), 24, true);
		RealtimeRunner runner = scene.runnerRealTime(new MultiChannelAudioOutput(out), List.of(0),
				BUFFER_SIZE, new RealtimeRunnerOptions().loopFrames(loopFrames).renderAheadSlots(4));

		try (out) {
			Assert.assertEquals(4, runner.renderAheadSlots());
			Assert.assertEquals("the override must not change the global depth",
					globalSlots, AudioSceneRealtimeRunner.renderAheadSlots);

			RealtimePositionWindow window = runner.getWindow();
			runner.setup().get().run();
			Runnable tick = runner.tick().get();

			for (int k = 0; k < 6; k++) {
				tick.run();
				double tickStart = scene.getTimeManager().getClock().getFrame() - BUFFER_SIZE;
				Assert.assertEquals("clock at the start of tick " + k,
						window.position((long) k * BUFFER_SIZE), tickStart, 0.0);
				Assert.assertTrue("clock wraps inside the loop", tickStart < loopFrames);
			}

			long target = 1500;
			runner.retarget(target);
			Assert.assertTrue("a retarget leaves a prefilled slot ready", runner.slotReady());
			Assert.assertEquals(0, runner.buffersConsumed());

			long deadline = System.currentTimeMillis() + 60_000;
			while (!runner.isRingFull() && System.currentTimeMillis() < deadline) {
				Thread.onSpinWait();
			}
			Assert.assertTrue("the producer fills the ring after a retarget", runner.isRingFull());

			tick.run();
			Assert.assertEquals("the first buffer after a retarget is mixed at the target",
					target, scene.getTimeManager().getClock().getFrame() - BUFFER_SIZE, 0.0);
			Assert.assertEquals(1, runner.buffersConsumed());
		} finally {
			runner.destroy();
		}
	}

	/**
	 * A retarget moves the automation clock to the new position the moment it returns, before
	 * the next tick runs, so a consumer that observes or mixes between the retarget and the next
	 * tick reads the new automation time rather than the stale one. Checked for a looping window
	 * after the clock has already advanced away from the target.
	 */
	@Test(timeout = 600_000)
	public void retargetMovesClockImmediately() {
		double loopFrames = BUFFER_SIZE * 2.5;

		AudioScene<?> scene = createBaselineScene(getSamplesDir(), 2);
		applyGenome(scene, GENOME_SEED);
		WaveOutput out = new WaveOutput(() -> new File("results/runner-retarget-clock.wav"), 24, true);
		RealtimeRunner runner = scene.runnerRealTime(new MultiChannelAudioOutput(out), List.of(0),
				BUFFER_SIZE, new RealtimeRunnerOptions().loopFrames(loopFrames).renderAheadSlots(4));

		try (out) {
			RealtimePositionWindow window = runner.getWindow();
			runner.setup().get().run();
			Runnable tick = runner.tick().get();

			tick.run();
			tick.run();
			Assert.assertNotEquals("the clock has advanced away from the target before the retarget",
					scene.getTimeManager().positionForFrame(window.position(0)),
					scene.getTimeManager().getClock().getFrame(), 0.0);

			long target = 1500;
			runner.retarget(target);
			Assert.assertEquals("the clock moves to the target the moment retarget returns",
					scene.getTimeManager().positionForFrame(window.position(0)),
					scene.getTimeManager().getClock().getFrame(), 0.0);
		} finally {
			runner.destroy();
		}
	}

	/**
	 * A runner built without options keeps the free-running clock for its ticks, but a retarget
	 * still moves the automation clock to the requested position: the retarget contract moves the
	 * clock regardless of whether per-tick synchronization is configured.
	 */
	@Test(timeout = 600_000)
	public void nullOptionsRetargetMovesClock() {
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), 2);
		applyGenome(scene, GENOME_SEED);
		WaveOutput out = new WaveOutput(() -> new File("results/runner-retarget-null.wav"), 24, true);
		RealtimeRunner runner = scene.runnerRealTime(new MultiChannelAudioOutput(out),
				List.of(0), BUFFER_SIZE, null);

		try (out) {
			RealtimePositionWindow window = runner.getWindow();
			runner.setup().get().run();
			Runnable tick = runner.tick().get();
			tick.run();

			long target = 1500;
			runner.retarget(target);
			Assert.assertEquals("a null-options retarget moves the clock to the target",
					scene.getTimeManager().positionForFrame(window.position(0)),
					scene.getTimeManager().getClock().getFrame(), 0.0);
		} finally {
			runner.destroy();
		}
	}
}
