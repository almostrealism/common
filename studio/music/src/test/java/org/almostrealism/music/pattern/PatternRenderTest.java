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

package org.almostrealism.music.pattern;

import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.Scale;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.notes.FileNoteSource;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * End-to-end rendering tests for a percussive pattern built from explicit
 * elements over a generated sample. A one-measure pattern with a single hit a
 * quarter of the way in is repeated over a two-measure arrangement, so the output
 * must be silent before each hit and between the hits, and both repetitions must
 * be identical.
 *
 * <p>The central property is that rendering the arrangement buffer by buffer
 * through {@link PatternAudioBuffer} (which exercises the note cache and its
 * eviction) produces the same audio as rendering it in a single call.</p>
 */
public class PatternRenderTest extends TestSuiteBase implements AudioTestFeatures {

	/** Frames per measure: half a second. */
	private static final int MEASURE_FRAMES = OutputLine.sampleRate / 2;

	/** Total frames of the two-measure arrangement. */
	private static final int TOTAL_FRAMES = 2 * MEASURE_FRAMES;

	/** Size of each streaming buffer; deliberately not a divisor of the arrangement. */
	private static final int BUFFER_SIZE = 4096;

	/** Duration of the generated sample in seconds. */
	private static final double SAMPLE_SECONDS = 0.1;

	/** The rendered channel. */
	private static final ChannelInfo CHANNEL =
			new ChannelInfo(0, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT);

	/**
	 * Creates a pattern system with one percussive one-measure pattern on channel 0
	 * holding a single hit at a quarter measure.
	 *
	 * @return the pattern system
	 */
	private PatternSystemManager system() {
		NoteAudioChoice choice = NoteAudioChoice.fromSource("Hit",
				new FileNoteSource(getNamedTestWavPath("render_hit.wav", 330.0, SAMPLE_SECONDS, true),
						WesternChromatic.C1), 0, 9, false);
		choice.setTuning(new DefaultKeyboardTuning());

		PatternSystemManager psm = new PatternSystemManager(List.of(choice),
				PatternSystemManagerTest.chromosomes(1));
		psm.init();
		psm.addPattern(0, 1.0, false).setExplicitElements(choice,
				List.of(new PatternElement(new PatternNote(0.1, 0.5, 0.9), 0.25)));
		return psm;
	}

	/**
	 * Creates the scene context for the two-measure arrangement.
	 *
	 * @param destination the destination buffer
	 * @return the context
	 */
	private static AudioSceneContext context(PackedCollection destination) {
		AudioSceneContext context = new AudioSceneContext();
		context.setMeasures(2);
		context.setFrames(TOTAL_FRAMES);
		context.setFrameForPosition(pos -> (int) (pos * MEASURE_FRAMES));
		context.setTimeForDuration(d -> d * 0.5);
		context.setScaleForPosition(pos -> Scale.of(WesternChromatic.C1));
		context.setChannels(List.of(CHANNEL));
		context.setDestination(destination);
		return context;
	}

	/**
	 * Renders the whole arrangement in one call of the pattern's sum operation.
	 *
	 * @param psm the pattern system
	 * @return the rendered frames
	 */
	private static double[] renderAtOnce(PatternSystemManager psm) {
		PackedCollection destination = new PackedCollection(TOTAL_FRAMES);
		AudioSceneContext context = context(destination);
		PatternLayerManager plm = psm.getPatterns().get(0);
		plm.updateDestination(context);
		plm.sum(() -> context, CHANNEL.getVoicing(), CHANNEL.getAudioChannel(),
				() -> 0, TOTAL_FRAMES).get().run();
		return destination.toArray(0, TOTAL_FRAMES);
	}

	/**
	 * Renders the arrangement buffer by buffer and concatenates the buffers.
	 *
	 * @param psm the pattern system
	 * @return the rendered frames
	 */
	private static double[] renderInBuffers(PatternSystemManager psm) {
		int[] frame = { 0 };
		AudioSceneContext context = context(null);
		PatternAudioBuffer buffer = new PatternAudioBuffer(psm, () -> context, CHANNEL,
				BUFFER_SIZE, () -> frame[0]);
		Assert.assertEquals(BUFFER_SIZE, buffer.getBufferSize());
		Assert.assertSame(CHANNEL, buffer.getChannel());
		Assert.assertNotNull(buffer.getOutputProducer());

		Runnable tick = buffer.prepareBatch().get();
		double[] result = new double[TOTAL_FRAMES];
		for (int start = 0; start < TOTAL_FRAMES; start += BUFFER_SIZE) {
			frame[0] = start;
			tick.run();

			int length = Math.min(BUFFER_SIZE, TOTAL_FRAMES - start);
			double[] out = buffer.getOutputBuffer().toArray(0, length);
			for (int i = 0; i < length; i++) {
				result[start + i] = out[i];
			}
		}

		return result;
	}

	/**
	 * Returns the largest absolute sample in {@code [from, to)}.
	 *
	 * @param audio the audio
	 * @param from  the first frame
	 * @param to    the frame after the last
	 * @return the peak magnitude
	 */
	private static double peak(double[] audio, int from, int to) {
		double max = 0.0;
		for (int i = from; i < to; i++) {
			max = Math.max(max, Math.abs(audio[i]));
		}
		return max;
	}

	/**
	 * Asserts the expected placement of the two hits: silence before each, sound
	 * after each onset, and identical audio for both repetitions.
	 *
	 * @param audio the rendered arrangement
	 */
	private static void assertHitPlacement(double[] audio) {
		int first = (int) (0.25 * MEASURE_FRAMES);
		int second = (int) (1.25 * MEASURE_FRAMES);
		int sampleFrames = (int) (SAMPLE_SECONDS * OutputLine.sampleRate);

		Assert.assertEquals("silence before the first hit", 0.0, peak(audio, 0, first), 0.0);
		Assert.assertTrue("the first hit sounds", peak(audio, first, first + sampleFrames) > 0.01);
		Assert.assertEquals("silence between the hits", 0.0,
				peak(audio, first + sampleFrames + 1, second), 0.0);
		Assert.assertTrue("the second hit sounds", peak(audio, second, second + sampleFrames) > 0.01);

		for (int i = 0; i < sampleFrames; i++) {
			Assert.assertEquals("repetitions differ at offset " + i,
					audio[first + i], audio[second + i], 1e-6);
		}
	}

	/** The per-note render path places each repetition of the hit at its onset. */
	@Test(timeout = 300000)
	public void perNoteRenderPlacesHits() {
		boolean batched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			assertHitPlacement(renderAtOnce(system()));
		} finally {
			PatternLayerManager.enableBatched = batched;
		}
	}

	/**
	 * Rendering buffer by buffer with the per-note path (note cache enabled)
	 * produces the same audio as rendering the arrangement in one call, and the
	 * pattern system volume scales the result.
	 */
	@Test(timeout = 300000)
	public void bufferedRenderMatchesSingleRender() {
		boolean batched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			PatternSystemManager psm = system();
			double[] whole = renderAtOnce(psm);
			double[] buffered = renderInBuffers(psm);
			assertHitPlacement(buffered);
			for (int i = 0; i < TOTAL_FRAMES; i++) {
				Assert.assertEquals("frame " + i, whole[i], buffered[i], 1e-6);
			}

			psm.setVolume(0.5);
			double[] quiet = renderInBuffers(psm);
			for (int i = 0; i < TOTAL_FRAMES; i++) {
				Assert.assertEquals("frame " + i, 0.5 * whole[i], quiet[i], 1e-6);
			}
		} finally {
			PatternLayerManager.enableBatched = batched;
		}
	}

	/**
	 * The batched render path places the hits the same way and is also
	 * independent of how the arrangement is divided into buffers.
	 */
	@Test(timeout = 300000)
	public void batchedRenderMatchesAcrossBuffers() {
		boolean batched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = true;

		try {
			PatternSystemManager psm = system();
			BatchedPatternLayerRenderer.resetCounters();
			double[] whole = renderAtOnce(psm);
			assertHitPlacement(whole);
			Assert.assertTrue("the batched renderer must dispatch (fallbacks: "
							+ BatchedPatternLayerRenderer.fallbackCount.get() + ")",
					BatchedPatternLayerRenderer.batchedDispatchCount.get() > 0);

			double[] buffered = renderInBuffers(psm);
			for (int i = 0; i < TOTAL_FRAMES; i++) {
				Assert.assertEquals("frame " + i, whole[i], buffered[i], 1e-6);
			}
		} finally {
			PatternLayerManager.enableBatched = batched;
		}
	}

	/**
	 * A channel with no patterns contributes nothing: its operation leaves the
	 * destination untouched even when the system volume is not unity.
	 */
	@Test(timeout = 120000)
	public void channelWithoutPatternsIsSilent() {
		PatternSystemManager psm = system();
		psm.setVolume(0.5);

		PackedCollection destination = new PackedCollection(BUFFER_SIZE).fill(1.0);
		AudioSceneContext context = context(destination);
		psm.sum(() -> context, new ChannelInfo(7, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT),
				() -> 0, BUFFER_SIZE).get().run();

		double[] out = destination.toArray(0, BUFFER_SIZE);
		for (int i = 0; i < BUFFER_SIZE; i++) {
			Assert.assertEquals("frame " + i, 1.0, out[i], 0.0);
		}
	}

	/**
	 * Warming the note cache evaluates one note per element of every pattern, and
	 * resetting a buffer clears whatever it held.
	 */
	@Test(timeout = 300000)
	public void warmNoteCacheEvaluatesEveryNote() {
		PatternSystemManager psm = system();
		psm.setVolume(0.5);

		int evaluated = psm.warmNoteCache(channel -> context(null));
		Assert.assertEquals(1, evaluated);

		PatternAudioBuffer buffer = new PatternAudioBuffer(psm, () -> context(null), CHANNEL,
				BUFFER_SIZE, () -> 0, new PackedCollection(BUFFER_SIZE).fill(2.0));
		buffer.reset();
		Assert.assertEquals(0.0, peak(buffer.getOutputBuffer().toArray(0, BUFFER_SIZE), 0, BUFFER_SIZE), 0.0);
	}
}
