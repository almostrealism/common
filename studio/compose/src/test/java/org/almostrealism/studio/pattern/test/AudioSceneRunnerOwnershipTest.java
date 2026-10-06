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
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

	/** Message of the simulated master-output wiring failure. */
	private static final String MASTER_FAILURE = "simulated master output wiring failure";

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
			Destroyable.destroy(runner);
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

			Destroyable.destroy(cellListRunner);
			assertEquals(1, runners.getLiveRunnerCount());
			Destroyable.destroy(cellListRunner);
			assertEquals("a repeated destroy() must not untrack another runner",
					1, runners.getLiveRunnerCount());

			runners.destroy();
			assertEquals(0, runners.getLiveRunnerCount());
			runners.destroy();
			Destroyable.destroy(pdslRunner);
			assertEquals(0, runners.getLiveRunnerCount());
		} finally {
			Destroyable.destroy(outputs);
			MixdownManager.enablePdslMixdown = pdsl;
		}
	}

	/**
	 * {@link AudioScene#runnerRealTime} returns the runner as a {@link TemporalCellular} — the
	 * integration type — rather than a concrete implementation or a bespoke destroyable subtype.
	 * A caller honoring the documented "destroy the runner when done" contract releases it through
	 * the sanctioned {@link Destroyable#destroy(Object)} helper, which destroys the runner when it
	 * is {@link Destroyable} without reaching around the interface to its implementation. Two
	 * successive runners released this way must each take the scene's live-runner count back to zero.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void runnerRealTimeRunnerIsReleasedViaDestroyableHelper() {
		boolean pdsl = MixdownManager.enablePdslMixdown;
		MixdownManager.enablePdslMixdown = false;
		List<WaveOutput> outputs = new ArrayList<>();
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), SOURCE_COUNT);

		try {
			applyGenome(scene, 1);
			assertEquals(0, scene.getLiveRunnerCount());

			// Released through the sanctioned static helper — no (Destroyable) cast required.
			TemporalCellular runner =
					scene.runnerRealTime(output("ownership-destroyable", outputs), List.of(0), BUFFER_SIZE);
			assertEquals(1, scene.getLiveRunnerCount());
			Destroyable.destroy(runner);
			assertEquals("Destroyable.destroy must release the runner returned as TemporalCellular",
					0, scene.getLiveRunnerCount());

			// A second runner, released the same way, must also return the count to zero.
			TemporalCellular second =
					scene.runnerRealTime(output("ownership-destroyable-2", outputs), List.of(0), BUFFER_SIZE);
			assertEquals(1, scene.getLiveRunnerCount());
			Destroyable.destroy(second);
			assertEquals("a second Destroyable.destroy must release the second runner",
					0, scene.getLiveRunnerCount());
		} finally {
			scene.destroy();
			Destroyable.destroy(outputs);
			MixdownManager.enablePdslMixdown = pdsl;
		}
	}

	/**
	 * {@link AudioScene#renderChannel} builds a real-time runner internally; it must destroy
	 * that runner when the render finishes rather than leave it in the scene's live-runner
	 * tracker. Two successive renders on the same long-lived scene must therefore each leave
	 * the live runner count at zero. Before the fix the method only {@code reset()} the
	 * runner, so every render accumulated a runner (and its ring, model, and argument
	 * buffers) until the whole scene was destroyed.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void renderChannelReleasesRunnerEachCall() {
		boolean pdsl = MixdownManager.enablePdslMixdown;
		MixdownManager.enablePdslMixdown = false;
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), SOURCE_COUNT);

		try {
			applyGenome(scene, 1);
			assertEquals(0, scene.getLiveRunnerCount());

			scene.renderChannel(0, BUFFER_SIZE, "results/render-channel-ownership.wav");
			assertEquals("renderChannel must destroy the runner it builds",
					0, scene.getLiveRunnerCount());

			scene.renderChannel(0, BUFFER_SIZE, "results/render-channel-ownership.wav");
			assertEquals("a second renderChannel must not accumulate a runner",
					0, scene.getLiveRunnerCount());
		} finally {
			scene.destroy();
			MixdownManager.enablePdslMixdown = pdsl;
		}
	}

	/**
	 * {@link AudioSceneRealtimeRunner#render} must release the runner it built when a later
	 * construction step fails, not only when the render completes. The runner built here is
	 * wrapped so that compiling its tick operation throws — after the runner exists and its
	 * setup operation is compiled, but before rendering starts. The original failure must
	 * propagate unchanged and the runner must no longer be live. Before the fix the runner
	 * and compiled operations were created outside the guarded scope, so such a failure left
	 * the runner tracked (and its buffers, plus the output's timeline buffer, allocated).
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void renderReleasesRunnerWhenConstructionFails() {
		boolean pdsl = MixdownManager.enablePdslMixdown;
		MixdownManager.enablePdslMixdown = false;
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), SOURCE_COUNT);

		try {
			applyGenome(scene, 1);
			AudioSceneRealtimeRunner runners = new AudioSceneRealtimeRunner(scene) {
				@Override
				public TemporalCellular create(MultiChannelAudioOutput output,
											   List<Integer> channels, int bufferSize) {
					return new FailingTick(super.create(output, channels, bufferSize));
				}
			};

			IllegalStateException thrown = null;
			try {
				runners.render(0, BUFFER_SIZE, "results/render-construction-failure.wav", BUFFER_SIZE);
			} catch (IllegalStateException e) {
				thrown = e;
			}

			assertTrue("the tick compilation failure should propagate", thrown != null);
			assertEquals(FailingTick.MESSAGE, thrown.getMessage());
			assertEquals("a runner whose render failed during construction must be released",
					0, runners.getLiveRunnerCount());
		} finally {
			scene.destroy();
			MixdownManager.enablePdslMixdown = pdsl;
		}
	}

	/**
	 * A PDSL runner build that fails after the mixdown model is compiled and its throwaway
	 * forward pass has run — here while wiring the master output — must roll back every
	 * resource it allocated (the compiled model and its output buffer among them, released
	 * model first) and rethrow the original failure unchanged. The scene's own buffers must
	 * be left intact: a second build on the same scene succeeds and is tracked normally.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void pdslRunnerBuildFailureRollsBackModelAndOutput() {
		boolean pdsl = MixdownManager.enablePdslMixdown;
		MixdownManager.enablePdslMixdown = true;
		List<WaveOutput> outputs = new ArrayList<>();
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), SOURCE_COUNT);

		try {
			applyGenome(scene, 1);
			AudioSceneRealtimeRunner runners = new AudioSceneRealtimeRunner(scene);
			int threads = producerThreadCount();

			WaveOutput failingOut = new WaveOutput(
					() -> new File("results/ownership-pdsl-failure.wav"), 24, true);
			outputs.add(failingOut);
			MultiChannelAudioOutput failing = new MultiChannelAudioOutput(failingOut) {
				@Override
				public Receptor<PackedCollection> getMaster(ChannelInfo.StereoChannel channel) {
					throw new IllegalStateException(MASTER_FAILURE);
				}
			};

			IllegalStateException thrown = null;
			try {
				runners.create(failing, null, BUFFER_SIZE);
			} catch (IllegalStateException e) {
				thrown = e;
			}

			assertTrue("the output wiring failure should propagate", thrown != null);
			assertEquals(MASTER_FAILURE, thrown.getMessage());
			assertEquals(0, thrown.getSuppressed().length);
			assertEquals("a failed build must not leave a runner tracked",
					0, runners.getLiveRunnerCount());
			assertEquals("a failed build must not start a producer thread",
					threads, producerThreadCount());

			TemporalCellular runner = runners.create(output("ownership-pdsl-retry", outputs),
					null, BUFFER_SIZE);
			assertEquals("the scene must remain usable after a rolled-back build",
					1, runners.getLiveRunnerCount());
			Destroyable.destroy(runner);
			assertEquals(0, runners.getLiveRunnerCount());
		} finally {
			scene.destroy();
			Destroyable.destroy(outputs);
			MixdownManager.enablePdslMixdown = pdsl;
		}
	}

	/**
	 * A runner whose build completes after {@link AudioSceneRealtimeRunner#destroy()} has
	 * begun must be refused rather than registered. Teardown has already taken its snapshot of
	 * live runners, so a runner tracked afterwards would never be stopped, and would go on
	 * reading the scene buffers the teardown frees. Teardown is triggered here from inside the
	 * PDSL build, after the model is compiled and while the master output is wired, which
	 * reproduces the race deterministically. The build must fail with an
	 * {@link IllegalStateException}, roll back what it allocated (no runner tracked, no
	 * producer thread), and every later {@code create} must be refused immediately.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void runnerBuiltDuringTeardownIsRefused() {
		boolean pdsl = MixdownManager.enablePdslMixdown;
		MixdownManager.enablePdslMixdown = true;
		List<WaveOutput> outputs = new ArrayList<>();
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), SOURCE_COUNT);

		try {
			applyGenome(scene, 1);
			AudioSceneRealtimeRunner runners = new AudioSceneRealtimeRunner(scene);
			int threads = producerThreadCount();

			WaveOutput racingOut = new WaveOutput(
					() -> new File("results/ownership-teardown-race.wav"), 24, true);
			outputs.add(racingOut);
			MultiChannelAudioOutput racing = new MultiChannelAudioOutput(racingOut) {
				@Override
				public Receptor<PackedCollection> getMaster(ChannelInfo.StereoChannel channel) {
					runners.destroy();
					return super.getMaster(channel);
				}
			};

			assertFalse(runners.isClosed());
			IllegalStateException thrown = null;
			try {
				runners.create(racing, null, BUFFER_SIZE);
			} catch (IllegalStateException e) {
				thrown = e;
			}

			assertTrue("a runner completed after teardown began must be refused", thrown != null);
			assertTrue(thrown.getMessage(), thrown.getMessage().contains("destroyed scene"));
			assertEquals(0, thrown.getSuppressed().length);
			assertTrue(runners.isClosed());
			assertEquals("a refused runner must not be tracked", 0, runners.getLiveRunnerCount());
			assertEquals("a refused runner must not start a producer thread",
					threads, producerThreadCount());

			IllegalStateException refused = null;
			try {
				runners.create(output("ownership-after-teardown", outputs), List.of(0), BUFFER_SIZE);
			} catch (IllegalStateException e) {
				refused = e;
			}

			assertTrue("create() after destroy() must be refused", refused != null);
			assertTrue(refused.getMessage(), refused.getMessage().contains("destroyed scene"));
			assertEquals(0, runners.getLiveRunnerCount());
		} finally {
			scene.destroy();
			Destroyable.destroy(outputs);
			MixdownManager.enablePdslMixdown = pdsl;
		}
	}

	/**
	 * A scene teardown that races a caller's in-progress runner release must wait for that
	 * release instead of returning while it is still running. The runner here blocks inside
	 * its release (standing in for a producer-thread join), and
	 * {@link AudioSceneRealtimeRunner#destroy()} is started on another thread meanwhile. While
	 * the release is blocked the runner must still count as live and teardown must not have
	 * returned; once the release finishes, teardown returns, the runner is gone, and its
	 * resources were released exactly once. Before the fix the runner was untracked as its
	 * release began, so teardown snapshotted nothing and returned at once — and the scene
	 * went on to free buffers the producer was still rendering into.
	 */
	@Test(timeout = 60_000)
	public void sceneTeardownWaitsForInProgressRunnerRelease() throws InterruptedException {
		CountDownLatch releasing = new CountDownLatch(1);
		Semaphore proceed = new Semaphore(0);
		AtomicInteger releases = new AtomicInteger();

		// The scene is never used: create() is overridden to build only the blocking runner.
		AudioSceneRealtimeRunner runners = new AudioSceneRealtimeRunner(null) {
			@Override
			public TemporalCellular create(MultiChannelAudioOutput output,
										   List<Integer> channels, int bufferSize) {
				BlockingReleaseRunner runner = new BlockingReleaseRunner(r -> release(r, List.of(() -> {
					releasing.countDown();
					proceed.acquireUninterruptibly();
					releases.incrementAndGet();
				})));
				track(runner);
				return runner;
			}
		};

		TemporalCellular runner = runners.create(null, null, BUFFER_SIZE);
		Thread caller = new Thread(() -> Destroyable.destroy(runner), "runner-release");
		caller.start();
		// Release on failure so the non-daemon caller cannot block forever on acquire.
		boolean releaseStarted = releasing.await(30, TimeUnit.SECONDS);
		if (!releaseStarted) proceed.release();
		assertTrue("the runner release must begin", releaseStarted);

		CountDownLatch teardownDone = new CountDownLatch(1);
		Thread teardown = new Thread(() -> {
			runners.destroy();
			teardownDone.countDown();
		}, "scene-teardown");
		teardown.start();

		// Unblock before asserting so a regression fails instead of hanging.
		boolean returnedEarly;
		int liveDuringRelease;
		try {
			returnedEarly = teardownDone.await(500, TimeUnit.MILLISECONDS);
			liveDuringRelease = runners.getLiveRunnerCount();
		} finally {
			proceed.release();
		}

		assertFalse("teardown must not return while a runner release is in progress", returnedEarly);
		assertEquals("a runner being released must still count as live", 1, liveDuringRelease);
		assertTrue("teardown must return once the release finishes",
				teardownDone.await(30, TimeUnit.SECONDS));
		caller.join(30_000);
		teardown.join(30_000);

		assertEquals(0, runners.getLiveRunnerCount());
		assertEquals("the runner must be released exactly once", 1, releases.get());
		Destroyable.destroy(runner);
		assertEquals("a repeated destroy() must not release again", 1, releases.get());
	}

	/**
	 * A scene teardown that races an in-progress runner build must wait for that build to finish
	 * before it returns, so the scene never frees the render buffers the build is still compiling
	 * against. The build here is paused inside the PDSL output wiring (its {@code getMaster} call),
	 * after the mixdown model has compiled but before the runner is tracked, and
	 * {@link AudioSceneRealtimeRunner#destroy()} is started on another thread meanwhile. While the
	 * build is paused, teardown must not have returned; once the build is released it reaches
	 * {@code track()}, is refused (teardown has begun), rolls back, and only then does teardown
	 * return. The refused build propagates an {@link IllegalStateException} and leaves no runner
	 * tracked. Before the build was tracked, teardown snapshotted no runners and returned at once,
	 * freeing buffers the build was still reading.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void sceneTeardownWaitsForInProgressRunnerBuild() throws InterruptedException {
		boolean pdsl = MixdownManager.enablePdslMixdown;
		MixdownManager.enablePdslMixdown = true;
		List<WaveOutput> outputs = new ArrayList<>();
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), SOURCE_COUNT);

		try {
			applyGenome(scene, 1);
			AudioSceneRealtimeRunner runners = new AudioSceneRealtimeRunner(scene);

			CountDownLatch building = new CountDownLatch(1);
			Semaphore proceed = new Semaphore(0);
			AtomicInteger masterCalls = new AtomicInteger();

			WaveOutput racingOut = new WaveOutput(
					() -> new File("results/ownership-build-race.wav"), 24, true);
			outputs.add(racingOut);
			// The first getMaster() call pauses the build mid-construction (after the model has
			// compiled, before track()); later calls pass through so rollback can complete.
			MultiChannelAudioOutput racing = new MultiChannelAudioOutput(racingOut) {
				@Override
				public Receptor<PackedCollection> getMaster(ChannelInfo.StereoChannel channel) {
					if (masterCalls.getAndIncrement() == 0) {
						building.countDown();
						proceed.acquireUninterruptibly();
					}
					return super.getMaster(channel);
				}
			};

			AtomicInteger thrown = new AtomicInteger();
			Thread builder = new Thread(() -> {
				try {
					runners.create(racing, null, BUFFER_SIZE);
				} catch (IllegalStateException e) {
					if (e.getMessage() != null && e.getMessage().contains("destroyed scene")) {
						thrown.incrementAndGet();
					}
				}
			}, "runner-build");
			builder.start();
			// Release on failure so the non-daemon builder cannot block forever on acquire.
			boolean buildPaused = building.await(120, TimeUnit.SECONDS);
			if (!buildPaused) proceed.release();
			assertTrue("the build should reach the paused output wiring", buildPaused);

			CountDownLatch teardownDone = new CountDownLatch(1);
			Thread teardown = new Thread(() -> {
				runners.destroy();
				teardownDone.countDown();
			}, "scene-teardown");
			teardown.start();

			// Unblock before asserting so a regression fails instead of hanging.
			boolean returnedEarly;
			try {
				returnedEarly = teardownDone.await(500, TimeUnit.MILLISECONDS);
			} finally {
				proceed.release();
			}

			assertFalse("teardown must not return while a runner build is in progress", returnedEarly);
			assertTrue("teardown must return once the build finishes",
					teardownDone.await(60, TimeUnit.SECONDS));
			builder.join(60_000);
			teardown.join(60_000);

			assertEquals("the build begun before teardown must be refused once it completes",
					1, thrown.get());
			assertEquals("a build refused during teardown must leave no runner tracked",
					0, runners.getLiveRunnerCount());
			assertTrue(runners.isClosed());
		} finally {
			scene.destroy();
			Destroyable.destroy(outputs);
			MixdownManager.enablePdslMixdown = pdsl;
		}
	}

	/**
	 * A runner whose {@code destroy()} hands itself to a caller-supplied destroyer, which
	 * releases it through the tracking collaborator's {@code release}, so a test can hold the
	 * release in progress.
	 */
	private static class BlockingReleaseRunner implements TemporalCellular, Destroyable {
		/** Releases this runner through the collaborator that tracks it. */
		private final Consumer<Destroyable> destroyer;

		/**
		 * Creates the runner.
		 *
		 * @param destroyer releases this runner through the collaborator that tracks it
		 */
		BlockingReleaseRunner(Consumer<Destroyable> destroyer) {
			this.destroyer = destroyer;
		}

		@Override
		public Supplier<Runnable> setup() {
			return () -> () -> { };
		}

		@Override
		public Supplier<Runnable> tick() {
			return () -> () -> { };
		}

		@Override
		public void destroy() {
			destroyer.accept(this);
		}
	}

	/**
	 * Delegates to a real runner except that compiling its tick operation throws. Destroying
	 * it destroys the real runner, so ownership is observable through the live-runner count.
	 */
	private static class FailingTick implements TemporalCellular, Destroyable {
		/** Message of the simulated tick compilation failure. */
		static final String MESSAGE = "simulated tick compilation failure";

		/** The real runner. */
		private final TemporalCellular delegate;

		/**
		 * Wraps the given runner.
		 *
		 * @param delegate the real runner
		 */
		FailingTick(TemporalCellular delegate) {
			this.delegate = delegate;
		}

		@Override
		public Supplier<Runnable> setup() {
			return delegate.setup();
		}

		@Override
		public Supplier<Runnable> tick() {
			return () -> {
				throw new IllegalStateException(MESSAGE);
			};
		}

		@Override
		public void destroy() {
			Destroyable.destroy(delegate);
		}
	}

	/**
	 * {@link AudioScene#destroyActiveCells} must release a cell list only when it is the
	 * scene's current active list. A list the scene does not currently track — one a later
	 * {@link AudioScene#getCells} already replaced, or a CellList runner's cells after the
	 * scene itself was torn down — must be left untouched: {@link CellList#destroy()}
	 * traverses the cell graph on every call, so a second traversal of a shared list would
	 * double-free its child resources. A real-time CellList runner shares its cells with the
	 * scene, and routing the runner's release through this method is what keeps the scene's
	 * own teardown from freeing the same graph twice.
	 */
	@Test(timeout = 120_000)
	public void destroyActiveCellsSkipsListThatIsNotActive() {
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), SOURCE_COUNT);

		try {
			CountingCellList notActive = new CountingCellList();

			scene.destroyActiveCells(notActive);
			assertEquals("a cell list that is not the scene's active list must not be destroyed",
					0, notActive.destroyCount);

			// The null guard must also be a no-op rather than throwing.
			scene.destroyActiveCells(null);
			assertEquals(0, notActive.destroyCount);
		} finally {
			scene.destroy();
		}
	}

	/**
	 * A release of the active cell list that throws part-way must still detach the list from
	 * the scene, so the scene's own teardown does not traverse it a second time. The active
	 * list built by {@link AudioScene#getCells} is given a data buffer whose release throws;
	 * {@link CellList#destroy()} stops at that throw without clearing its data, so a second
	 * traversal would release the same buffer again. Exactly one release must be observed
	 * across the failing {@link AudioScene#destroyActiveCells} and the subsequent
	 * {@link AudioScene#destroy()}.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void failedActiveCellsReleaseIsNotRepeatedBySceneDestroy() {
		boolean pdsl = MixdownManager.enablePdslMixdown;
		MixdownManager.enablePdslMixdown = false;
		List<WaveOutput> outputs = new ArrayList<>();
		PackedCollection frame = new PackedCollection(1);
		ThrowingCollection failing = new ThrowingCollection();

		try {
			AudioScene<?> scene = createBaselineScene(getSamplesDir(), SOURCE_COUNT);
			applyGenome(scene, 1);
			CellList active = (CellList) scene.getCells(output("ownership-active", outputs),
					List.of(0), BUFFER_SIZE, () -> 0, cp(frame));
			active.addData(failing);

			IllegalStateException thrown = null;
			try {
				scene.destroyActiveCells(active);
			} catch (IllegalStateException e) {
				thrown = e;
			}
			assertTrue("the failing data release should propagate", thrown != null);
			assertEquals(1, failing.destroyCount);

			scene.destroy();
			assertEquals("scene.destroy() must not traverse an active list whose release already ran",
					1, failing.destroyCount);
		} finally {
			frame.destroy();
			Destroyable.destroy(outputs);
			MixdownManager.enablePdslMixdown = pdsl;
		}
	}

	/**
	 * Concurrent releases of the scene's cell lists must free the active list exactly once and
	 * must never clear a newer list through a stale release of an older one. The scene builds
	 * list {@code A} and then rebuilds list {@code B} (which releases {@code A}); several threads
	 * then race, half releasing the stale {@code A} and half releasing the active {@code B}.
	 * Exactly one release of {@code B} must be observed, and the scene's own teardown must not
	 * release it again. Before the check-and-clear was a compare-and-set, two releasers could
	 * both pass the {@code activeCells == cells} check and free the graph twice.
	 *
	 * @throws InterruptedException if interrupted while waiting for the releasing threads
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void concurrentActiveCellsReleaseFreesActiveListOnce() throws InterruptedException {
		boolean pdsl = MixdownManager.enablePdslMixdown;
		MixdownManager.enablePdslMixdown = false;
		List<WaveOutput> outputs = new ArrayList<>();
		PackedCollection frame = new PackedCollection(1);
		CountingCollection counting = new CountingCollection();
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), SOURCE_COUNT);
		boolean sceneDestroyed = false;

		try {
			applyGenome(scene, 1);
			CellList stale = (CellList) scene.getCells(output("ownership-stale", outputs),
					List.of(0), BUFFER_SIZE, () -> 0, cp(frame));
			CellList active = (CellList) scene.getCells(output("ownership-rebuilt", outputs),
					List.of(0), BUFFER_SIZE, () -> 0, cp(frame));
			active.addData(counting);

			int releasers = 8;
			CyclicBarrier start = new CyclicBarrier(releasers);
			List<Thread> threads = new ArrayList<>();
			for (int i = 0; i < releasers; i++) {
				CellList target = i % 2 == 0 ? stale : active;
				Thread t = new Thread(() -> {
					try {
						start.await(30, TimeUnit.SECONDS);
					} catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
						return;
					}
					scene.destroyActiveCells(target);
				}, "active-cells-release-" + i);
				t.setDaemon(true);
				threads.add(t);
				t.start();
			}

			for (Thread t : threads) {
				t.join(60_000);
			}

			assertEquals("the active list must be released exactly once across racing releasers",
					1, counting.destroyCount.get());

			sceneDestroyed = true;
			scene.destroy();
			assertEquals("scene.destroy() must not release an active list a releaser already freed",
					1, counting.destroyCount.get());
		} finally {
			if (!sceneDestroyed) scene.destroy();
			frame.destroy();
			Destroyable.destroy(outputs);
			MixdownManager.enablePdslMixdown = pdsl;
		}
	}

	/** A {@link PackedCollection} whose release counts invocations, safe across threads. */
	private static class CountingCollection extends PackedCollection {
		/** Number of times {@link #destroy()} has been called. */
		private final AtomicInteger destroyCount = new AtomicInteger();

		/** Creates a single-element collection. */
		CountingCollection() {
			super(1);
		}

		@Override
		public void destroy() {
			destroyCount.incrementAndGet();
			super.destroy();
		}
	}

	/** A {@link PackedCollection} whose release counts invocations and then throws. */
	private static class ThrowingCollection extends PackedCollection {
		/** Number of times {@link #destroy()} has been called. */
		private int destroyCount;

		/** Creates a single-element collection. */
		ThrowingCollection() {
			super(1);
		}

		@Override
		public void destroy() {
			destroyCount++;
			super.destroy();
			throw new IllegalStateException("simulated release failure");
		}
	}

	/** A {@link CellList} that records how many times it has been destroyed. */
	private static class CountingCellList extends CellList {
		/** Number of times {@link #destroy()} has been called. */
		private int destroyCount;

		@Override
		public void destroy() {
			destroyCount++;
			super.destroy();
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
