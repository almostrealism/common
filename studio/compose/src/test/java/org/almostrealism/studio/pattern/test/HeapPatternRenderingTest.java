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

import org.almostrealism.studio.AudioScene;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.audio.tone.WesternScales;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.Heap;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

/**
 * Tests that pattern rendering works correctly inside a {@link Heap}.
 *
 * <p>The desktop app wraps pattern generation in a Heap to limit memory.
 * {@link Heap#stage(Runnable)} is a no-op without an active Heap, so tests
 * that run without one never exercise the push/pop lifecycle that frees
 * staged allocations. These tests reproduce the desktop app's Heap context
 * to verify that evaluated note audio survives long enough for summation.</p>
 */
public class HeapPatternRenderingTest extends AudioSceneTestBase {

	private static final int HEAP_SIZE = 64 * 1024 * 1024;

	@BeforeClass
	public static void initProcessing() {
		AudioProcessingUtils.init();
	}

	/** Single layer sum inside a Heap produces non-silent audio. */
	@Test(timeout = 120_000)
	public void singleLayerSumInHeap() {
		AudioScene<?> scene = createSceneWithGenome();
		int frames = SAMPLE_RATE * 4;
		PackedCollection dest = new PackedCollection(frames);

		Heap heap = new Heap(HEAP_SIZE);
		heap.use(() -> renderLayer(findRequiredLayer(scene), scene, dest,
				frames, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
				0, frames));

		assertNonSilent(dest, "singleLayerSumInHeap");
	}

	/** Baseline: sum without a Heap produces non-silent audio. */
	@Test(timeout = 120_000)
	public void singleLayerSumWithoutHeap() {
		AudioScene<?> scene = createSceneWithGenome();
		int frames = SAMPLE_RATE * 4;
		PackedCollection dest = new PackedCollection(frames);

		renderLayer(findRequiredLayer(scene), scene, dest,
				frames, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
				0, frames);

		assertNonSilent(dest, "singleLayerSumWithoutHeap");
	}

	/** Multiple consecutive sum() calls inside a single Heap session. */
	@Test(timeout = 180_000)
	public void multipleLayerSumsInHeap() {
		AudioScene<?> scene = createSceneWithGenome();
		int frames = SAMPLE_RATE * 4;

		Heap heap = new Heap(HEAP_SIZE);
		heap.use(() -> {
			for (PatternLayerManager plm : scene.getPatternManager().getPatterns()) {
				if (plm.getAllElements(0.0, plm.getDuration()).isEmpty()) continue;

				PackedCollection dest = new PackedCollection(frames);
				renderLayer(plm, scene, dest, frames,
						ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
						0, frames);
				assertNonSilent(dest, "channel " + plm.getChannel());
				return;
			}
			Assert.fail("No pattern layers with elements found");
		});
	}

	/** Half-second buffer inside a Heap (real-time buffer size). */
	@Test(timeout = 120_000)
	public void smallBufferSumInHeap() {
		AudioScene<?> scene = createSceneWithGenome();
		int bufferSize = SAMPLE_RATE / 2;
		int totalFrames = SAMPLE_RATE * 8;
		PackedCollection dest = new PackedCollection(bufferSize);

		Heap heap = new Heap(HEAP_SIZE);
		heap.use(() -> renderLayer(findRequiredLayer(scene), scene, dest,
				totalFrames, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
				0, bufferSize));

		assertNonSilent(dest, "smallBufferSumInHeap");
	}

	/** Multiple consecutive small buffers inside a Heap (multi-tick). */
	@Test(timeout = 180_000)
	public void multiBufferTicksInHeap() {
		AudioScene<?> scene = createSceneWithGenome();
		int bufferSize = SAMPLE_RATE / 2;
		int totalFrames = SAMPLE_RATE * 8;
		int ticks = 4;
		boolean[] anyNonSilent = {false};

		Heap heap = new Heap(HEAP_SIZE);
		heap.use(() -> {
			PatternLayerManager plm = findRequiredLayer(scene);
			for (int t = 0; t < ticks; t++) {
				PackedCollection dest = new PackedCollection(bufferSize);
				renderLayer(plm, scene, dest, totalFrames,
						ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
						t * bufferSize, bufferSize);
				if (hasNonZeroSamples(dest)) anyNonSilent[0] = true;
			}
		});

		Assert.assertTrue("All " + ticks + " buffer ticks were silent", anyNonSilent[0]);
	}

	/** Heap result produces non-silent audio just like no-Heap. */
	@Test(timeout = 180_000)
	public void heapMatchesNonHeap() {
		AudioScene<?> scene = createSceneWithGenome();
		int frames = SAMPLE_RATE * 4;
		PatternLayerManager plm = findRequiredLayer(scene);

		PackedCollection noHeapDest = new PackedCollection(frames);
		renderLayer(plm, scene, noHeapDest, frames,
				ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT, 0, frames);

		PackedCollection heapDest = new PackedCollection(frames);
		Heap heap = new Heap(HEAP_SIZE);
		heap.use(() -> renderLayer(plm, scene, heapDest, frames,
				ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT, 0, frames));

		assertNonSilent(noHeapDest, "heapMatchesNonHeap (no-heap)");
		assertNonSilent(heapDest, "heapMatchesNonHeap (heap)");
	}

	/** WET voicing renders inside a Heap without errors. */
	@Test(timeout = 120_000)
	public void wetVoicingSumInHeap() {
		AudioScene<?> scene = createSceneWithGenome();
		int frames = SAMPLE_RATE * 4;
		PackedCollection dest = new PackedCollection(frames);

		Heap heap = new Heap(HEAP_SIZE);
		heap.use(() -> renderLayer(findRequiredLayer(scene), scene, dest,
				frames, ChannelInfo.Voicing.WET, ChannelInfo.StereoChannel.LEFT,
				0, frames));
	}

	/** Small (16 MB) Heap stresses the stage push/pop lifecycle. */
	@Test(timeout = 120_000)
	public void smallHeapRendering() {
		AudioScene<?> scene = createSceneWithGenome();
		int frames = SAMPLE_RATE * 2;
		PackedCollection dest = new PackedCollection(frames);

		Heap heap = new Heap(16 * 1024 * 1024);
		heap.use(() -> renderLayer(findRequiredLayer(scene), scene, dest,
				frames, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
				0, frames));

		assertNonSilent(dest, "smallHeapRendering");
	}

	/** Both stereo channels render inside a Heap. */
	@Test(timeout = 120_000)
	public void stereoRenderInHeap() {
		AudioScene<?> scene = createSceneWithGenome();
		int frames = SAMPLE_RATE * 4;
		PatternLayerManager plm = findRequiredLayer(scene);

		PackedCollection leftDest = new PackedCollection(frames);
		PackedCollection rightDest = new PackedCollection(frames);

		Heap heap = new Heap(HEAP_SIZE);
		heap.use(() -> {
			renderLayer(plm, scene, leftDest, frames,
					ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT, 0, frames);
			renderLayer(plm, scene, rightDest, frames,
					ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.RIGHT, 0, frames);
		});

		Assert.assertTrue("Both stereo channels are silent",
				hasNonZeroSamples(leftDest) || hasNonZeroSamples(rightDest));
	}

	/** Scene clone + render inside a Heap (mirrors ChannelPatternController). */
	@Test(timeout = 300_000)
	public void cloneAndRenderInHeap() {
		AudioScene<?> scene = createSceneWithGenome();
		AudioScene<?> cloned = scene.clone();
		int frames = SAMPLE_RATE * 4;
		PackedCollection dest = new PackedCollection(frames);

		Heap heap = new Heap(HEAP_SIZE);
		heap.use(() -> renderLayer(findRequiredLayer(cloned), cloned, dest,
				frames, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
				0, frames));

		assertNonSilent(dest, "cloneAndRenderInHeap");
	}

	private AudioScene<?> createSceneWithGenome() {
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), 2);
		long seed = findWorkingGenomeSeed(scene, getSamplesDir());
		Assert.assertTrue("No working genome seed found", seed >= 0);
		applyGenome(scene, seed);
		return scene;
	}

	private PatternLayerManager findRequiredLayer(AudioScene<?> scene) {
		PatternLayerManager best = null;
		int bestCount = 0;
		for (PatternLayerManager plm : scene.getPatternManager().getPatterns()) {
			int count = plm.getAllElements(0.0, plm.getDuration()).size();
			if (count > bestCount) {
				bestCount = count;
				best = plm;
			}
		}
		Assert.assertNotNull("No active pattern layer found", best);
		return best;
	}

	private void renderLayer(PatternLayerManager plm, AudioScene<?> scene,
							 PackedCollection destination, int totalFrames,
							 ChannelInfo.Voicing voicing, ChannelInfo.StereoChannel stereo,
							 int startFrame, int frameCount) {
		AudioSceneContext ctx = buildContext(plm, scene, destination, totalFrames,
				voicing, stereo);
		plm.sum(() -> ctx, voicing, stereo,
				() -> startFrame, frameCount).get().run();
	}

	private AudioSceneContext buildContext(PatternLayerManager plm, AudioScene<?> scene,
										  PackedCollection destination, int totalFrames,
										  ChannelInfo.Voicing voicing,
										  ChannelInfo.StereoChannel stereo) {
		int measures = scene.getTotalMeasures();
		double framesPerMeasure = (double) totalFrames / measures;

		AudioSceneContext ctx = new AudioSceneContext();
		ctx.setMeasures(measures);
		ctx.setFrames(totalFrames);
		ctx.setFrameForPosition(pos -> (int) (pos * framesPerMeasure));
		ctx.setTimeForDuration(pos -> pos * framesPerMeasure / SAMPLE_RATE);
		ctx.setDestination(destination);
		ctx.setActivityBias(1.0);
		try {
			scene.getChordProgression().forPosition(0);
			ctx.setScaleForPosition(scene.getChordProgression()::forPosition);
		} catch (NullPointerException e) {
			ctx.setScaleForPosition(pos -> WesternScales.major(WesternChromatic.C3, 2));
		}
		ctx.setChannels(List.of(new ChannelInfo(plm.getChannel(), voicing, stereo)));
		plm.updateDestination(ctx);
		return ctx;
	}

	private boolean hasNonZeroSamples(PackedCollection collection) {
		for (int i = 0; i < collection.getMemLength(); i++) {
			if (collection.toDouble(i) != 0.0) return true;
		}
		return false;
	}

	private void assertNonSilent(PackedCollection collection, String label) {
		Assert.assertTrue(label + ": output is completely silent",
				hasNonZeroSamples(collection));
	}
}
