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

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.Scale;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.notes.NoteAudioContext;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.SimplePatternNote;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link ScaleTraversalStrategy#getNoteDestinations}, the live pattern
 * render path.
 *
 * <p>A scale position of exactly {@code 1.0} is an ordinary input (the top of a
 * chord); the existing MIDI-export tests use {@code List.of(0.0, 0.5, 1.0)}
 * verbatim. This test renders such a chord through {@code getNoteDestinations} to
 * confirm that a boundary scale position does not select a key index one past the
 * end of the scale.</p>
 */
public class ScaleTraversalStrategyTest extends TestSuiteBase {

	/** Standard BPM for the timing context. */
	private static final double BPM = 120.0;

	/** Beats per measure (4/4 time). */
	private static final int BEATS_PER_MEASURE = 4;

	/**
	 * Builds a {@link PatternElement} with a small in-memory note so that its
	 * rendered destinations can be produced without loading audio assets.
	 *
	 * @param strategy the traversal strategy to apply
	 * @param positions the scale positions to traverse
	 * @param repeatCount the number of repetitions
	 * @return a renderable pattern element
	 */
	private PatternElement renderableElement(ScaleTraversalStrategy strategy,
											 List<Double> positions, int repeatCount) {
		PatternElement element = new PatternElement(
				Map.of(ChannelInfo.Voicing.MAIN, buildNote()), 0.0);
		configureElement(element, strategy, positions, repeatCount);
		return element;
	}

	/**
	 * Builds a {@link RecordingElement} that captures the destinations it produces,
	 * configured identically to {@link #renderableElement}.
	 *
	 * @param strategy the traversal strategy to apply
	 * @param positions the scale positions to traverse
	 * @param repeatCount the number of repetitions
	 * @return a renderable, recording pattern element
	 */
	private RecordingElement recordingElement(ScaleTraversalStrategy strategy,
											  List<Double> positions, int repeatCount) {
		RecordingElement element = new RecordingElement(
				Map.of(ChannelInfo.Voicing.MAIN, buildNote()), 0.0);
		configureElement(element, strategy, positions, repeatCount);
		return element;
	}

	/**
	 * Builds a small in-memory {@link PatternNote} so destinations can be produced
	 * without loading audio assets.
	 *
	 * @return the note
	 */
	private PatternNote buildNote() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();
		PackedCollection source = new PackedCollection(1024);
		NoteAudioProvider provider = NoteAudioProvider.create(() -> source, WesternChromatic.C1);
		provider.setTuning(tuning);
		return new PatternNote(new SimplePatternNote(provider), null);
	}

	/**
	 * Applies the shared traversal, duration, and repeat configuration to an element.
	 *
	 * @param element the element to configure
	 * @param strategy the traversal strategy to apply
	 * @param positions the scale positions to traverse
	 * @param repeatCount the number of repetitions
	 */
	private void configureElement(PatternElement element, ScaleTraversalStrategy strategy,
								  List<Double> positions, int repeatCount) {
		element.setScaleTraversalStrategy(strategy);
		element.setScalePosition(positions);
		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.25);
		element.setRepeatCount(repeatCount);
		element.setRepeatDuration(0.25);
	}

	/**
	 * A {@link PatternElement} that records the {@link RenderedNoteAudio} instances it
	 * returns from {@link #getNoteDestinations}, so a test can inspect the lifecycle of
	 * the transient notes a render path gathers.
	 */
	private static final class RecordingElement extends PatternElement {
		/** The destinations this element has produced, across every gather call. */
		private final List<RenderedNoteAudio> recorded = new ArrayList<>();

		/**
		 * Creates a recording element with the given notes and position.
		 *
		 * @param notes the notes keyed by voicing
		 * @param position the position of this element within its pattern, in measures
		 */
		RecordingElement(Map<ChannelInfo.Voicing, PatternNote> notes, double position) {
			super(notes, position);
		}

		@Override
		public List<RenderedNoteAudio> getNoteDestinations(boolean melodic, double offset,
														   AudioSceneContext context,
														   NoteAudioContext audioContext) {
			List<RenderedNoteAudio> result =
					super.getNoteDestinations(melodic, offset, context, audioContext);
			recorded.addAll(result);
			return result;
		}

		/** Returns every destination produced so far. */
		List<RenderedNoteAudio> getRecorded() {
			return recorded;
		}
	}

	/** Marker exception thrown by {@link ThrowingElement} to simulate a gather failure. */
	private static final class GatherFailure extends RuntimeException {
		/** Creates the marker gather failure with a fixed message. */
		GatherFailure() {
			super("simulated gather failure");
		}
	}

	/**
	 * A {@link PatternElement} whose {@link #getNoteDestinations} always throws, used to
	 * simulate a gather that fails partway through a multi-element render so a test can
	 * verify the notes gathered from earlier elements are still released.
	 */
	private static final class ThrowingElement extends PatternElement {
		/**
		 * Creates a throwing element with the given notes and position.
		 *
		 * @param notes the notes keyed by voicing
		 * @param position the position of this element within its pattern, in measures
		 */
		ThrowingElement(Map<ChannelInfo.Voicing, PatternNote> notes, double position) {
			super(notes, position);
		}

		@Override
		public List<RenderedNoteAudio> getNoteDestinations(boolean melodic, double offset,
														   AudioSceneContext context,
														   NoteAudioContext audioContext) {
			throw new GatherFailure();
		}
	}

	/**
	 * Creates an {@link AudioSceneContext} whose scale is fixed at every position.
	 *
	 * @param scale the scale to return at all positions
	 * @return the configured context
	 */
	private AudioSceneContext context(Scale<?> scale) {
		double secondsPerMeasure = (60.0 / BPM) * BEATS_PER_MEASURE;
		double framesPerMeasure = secondsPerMeasure * OutputLine.sampleRate;

		AudioSceneContext context = new AudioSceneContext();
		context.setMeasures(4);
		context.setFrames((int) (4 * framesPerMeasure));
		context.setFrameForPosition(pos -> (int) (pos * framesPerMeasure));
		context.setTimeForDuration(dur -> dur * secondsPerMeasure);
		context.setScaleForPosition(pos -> scale);
		return context;
	}

	/**
	 * Builds a {@link NoteAudioContext} for MAIN/LEFT voicing selecting the given note.
	 *
	 * @param note the note audio to select
	 * @return the configured audio context
	 */
	private NoteAudioContext audioContext(PatternNote note) {
		return audioContext(ChannelInfo.StereoChannel.LEFT, note);
	}

	/**
	 * Builds a {@link NoteAudioContext} for MAIN voicing on the given stereo channel
	 * selecting the given note.
	 *
	 * @param channel the stereo channel to render
	 * @param note    the note audio to select
	 * @return the configured audio context
	 */
	private NoteAudioContext audioContext(ChannelInfo.StereoChannel channel, PatternNote note) {
		return new NoteAudioContext(
				ChannelInfo.Voicing.MAIN,
				channel,
				d -> note,
				pos -> pos + 1.0);
	}

	/**
	 * A chord whose last scale position is exactly {@code 1.0} must render one
	 * destination per position rather than throwing when the boundary position
	 * maps to a key index one past the end of the (shrinking) key list.
	 */
	@Test(timeout = 120000)
	public void chordWithTopScalePositionRenders() {
		boolean previousBatched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			Scale<?> scale = Scale.of(WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);

			PatternElement element = renderableElement(
					ScaleTraversalStrategy.CHORD, List.of(0.0, 0.5, 1.0), 1);

			List<RenderedNoteAudio> destinations = element.getNoteDestinations(
					true, 0.0, context(scale), audioContext(element.getNote(ChannelInfo.Voicing.MAIN)));

			Assert.assertEquals("Chord should produce one destination per scale position",
					3, destinations.size());
		} finally {
			PatternLayerManager.enableBatched = previousBatched;
		}
	}

	/**
	 * Coincident chord tones share a frame offset but must carry distinct cache
	 * identities, so the note-audio cache does not conflate them during buffered
	 * per-note rendering.
	 */
	@Test(timeout = 120000)
	public void coincidentChordTonesHaveDistinctCacheIdentities() {
		boolean previousBatched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			Scale<?> scale = Scale.of(WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);

			PatternElement element = renderableElement(
					ScaleTraversalStrategy.CHORD, List.of(0.0, 0.5, 1.0), 1);

			List<RenderedNoteAudio> destinations = element.getNoteDestinations(
					true, 0.0, context(scale), audioContext(element.getNote(ChannelInfo.Voicing.MAIN)));

			Assert.assertEquals(3, destinations.size());

			int offset = destinations.get(0).getOffset();
			for (RenderedNoteAudio note : destinations) {
				Assert.assertEquals("chord tones are coincident", offset, note.getOffset());
				Assert.assertNotNull("each chord tone carries a cache identity", note.getCacheIdentity());
			}

			Assert.assertNotEquals(destinations.get(0).getCacheIdentity(),
					destinations.get(1).getCacheIdentity());
			Assert.assertNotEquals(destinations.get(0).getCacheIdentity(),
					destinations.get(2).getCacheIdentity());
			Assert.assertNotEquals(destinations.get(1).getCacheIdentity(),
					destinations.get(2).getCacheIdentity());
		} finally {
			PatternLayerManager.enableBatched = previousBatched;
		}
	}

	/**
	 * The LEFT and RIGHT renders of one note read different channels of the source
	 * audio, and a single {@link NoteAudioCache} serves both channels of a layer, so
	 * their cache identities must differ even though {@link ElementVoicingDetails}
	 * equality ignores the stereo channel. Rendering the same channel twice must
	 * still produce equal identities so a note spanning buffers hits its own entry.
	 */
	@Test(timeout = 120000)
	public void stereoChannelsHaveDistinctCacheIdentities() {
		boolean previousBatched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			Scale<?> scale = Scale.of(WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);

			PatternElement element = renderableElement(
					ScaleTraversalStrategy.CHORD, List.of(0.0), 1);
			PatternNote note = element.getNote(ChannelInfo.Voicing.MAIN);

			RenderedNoteAudio left = element.getNoteDestinations(true, 0.0, context(scale),
					audioContext(ChannelInfo.StereoChannel.LEFT, note)).get(0);
			RenderedNoteAudio leftAgain = element.getNoteDestinations(true, 0.0, context(scale),
					audioContext(ChannelInfo.StereoChannel.LEFT, note)).get(0);
			RenderedNoteAudio right = element.getNoteDestinations(true, 0.0, context(scale),
					audioContext(ChannelInfo.StereoChannel.RIGHT, note)).get(0);

			Assert.assertEquals("both channels start at the same frame",
					left.getOffset(), right.getOffset());
			Assert.assertEquals("the same note on the same channel keeps its identity",
					left.getCacheIdentity(), leftAgain.getCacheIdentity());
			Assert.assertNotEquals("LEFT and RIGHT renders must not share a cache entry",
					left.getCacheIdentity(), right.getCacheIdentity());

			NoteAudioCache cache = new NoteAudioCache();
			try {
				PackedCollection leftAudio = new PackedCollection(4);
				PackedCollection rightAudio = new PackedCollection(4);
				cache.put(left.getOffset(), left.getCacheIdentity(), leftAudio);
				cache.put(right.getOffset(), right.getCacheIdentity(), rightAudio);

				Assert.assertEquals(2, cache.size());
				Assert.assertSame(leftAudio, cache.get(leftAgain.getOffset(), leftAgain.getCacheIdentity()));
				Assert.assertSame(rightAudio, cache.get(right.getOffset(), right.getCacheIdentity()));
			} finally {
				cache.clear();
			}
		} finally {
			PatternLayerManager.enableBatched = previousBatched;
		}
	}

	/**
	 * A sequence step whose scale position is exactly {@code 1.0} must select the
	 * final key of the scale rather than an index one past the end.
	 */
	@Test(timeout = 120000)
	public void sequenceWithTopScalePositionRenders() {
		boolean previousBatched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			Scale<?> scale = Scale.of(WesternChromatic.C4);

			PatternElement element = renderableElement(
					ScaleTraversalStrategy.SEQUENCE, List.of(1.0), 1);

			List<RenderedNoteAudio> destinations = element.getNoteDestinations(
					true, 0.0, context(scale), audioContext(element.getNote(ChannelInfo.Voicing.MAIN)));

			Assert.assertEquals("Sequence step should produce one destination",
					1, destinations.size());
		} finally {
			PatternLayerManager.enableBatched = previousBatched;
		}
	}

	/**
	 * The batched renderer memoizes its melodic gathers, and each gathered
	 * {@link RenderedNoteAudio} owns a single-element offset-argument
	 * {@link PackedCollection}. Tearing down the owning {@link PatternLayerManager}
	 * must clear the renderer's gather cache and destroy those offset arguments, so
	 * the native allocations do not survive scene churn until garbage collection.
	 */
	@Test(timeout = 120000)
	public void batchedRendererTeardownReleasesGatheredOffsetArgs() {
		boolean previousBatched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			Scale<?> scale = Scale.of(WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);
			PatternElement element = renderableElement(
					ScaleTraversalStrategy.CHORD, List.of(0.0, 0.5, 1.0), 1);
			PatternNote note = element.getNote(ChannelInfo.Voicing.MAIN);

			PatternLayerManager plm = new PatternLayerManager(List.of(),
					new ProjectedGenome(8).addChromosome(), 0, 4.0, true);
			BatchedPatternLayerRenderer renderer = plm.getBatchedLayerRenderer();

			List<RenderedNoteAudio> gathered = renderer.gatherMelodic(List.of(element), 0.0,
					context(scale), audioContext(note));
			Assert.assertTrue("the chord gathers one destination per tone", gathered.size() == 3);
			Assert.assertTrue("the gather is memoized under a single key", renderer.gatherCacheSize() == 1);

			List<PackedCollection> offsetArgs = gathered.stream()
					.map(RenderedNoteAudio::getOffsetArg)
					.toList();
			offsetArgs.forEach(arg -> {
				Assert.assertNotNull("each gathered note owns an offset argument", arg);
				Assert.assertFalse("a live note's offset argument is not destroyed", arg.isDestroyed());
			});

			plm.destroy();

			Assert.assertTrue("teardown clears the renderer's gather cache", renderer.gatherCacheSize() == 0);
			offsetArgs.forEach(arg -> Assert.assertTrue(
					"teardown destroys each gathered note's offset argument", arg.isDestroyed()));

			plm.destroy();
			Assert.assertTrue("a repeated teardown is a no-op", renderer.gatherCacheSize() == 0);
		} finally {
			PatternLayerManager.enableBatched = previousBatched;
		}
	}

	/**
	 * The per-note render path gathers fresh {@link RenderedNoteAudio} on every tick (it
	 * does not memoize), and each owns a single-element offset-argument
	 * {@link PackedCollection} nothing else references once the dispatch returns.
	 * {@link PatternFeatures#renderPerNote} must release those offset arguments so a
	 * long-running per-note render does not accumulate native allocations until GC.
	 *
	 * <p>A render window past every note's estimated end makes {@code renderNotes} skip
	 * them all, so the finally-release under test is exercised without compiling or
	 * evaluating a kernel. A released note has its offset argument destroyed and its
	 * reference nulled by {@link RenderedNoteAudio#destroy()}.</p>
	 */
	@Test(timeout = 120000)
	public void renderPerNoteReleasesTransientOffsetArgs() {
		boolean previousBatched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			Scale<?> scale = Scale.of(WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);
			RecordingElement element = recordingElement(
					ScaleTraversalStrategy.CHORD, List.of(0.0, 0.5, 1.0), 1);

			AudioSceneContext ctx = context(scale);
			ctx.setDestination(new PackedCollection(1));

			PatternLayerManager plm = new PatternLayerManager(List.of(),
					new ProjectedGenome(8).addChromosome(), 0, 4.0, true);
			BatchedPatternLayerRenderer features = plm.getBatchedLayerRenderer();

			features.renderPerNote(ctx, audioContext(element.getNote(ChannelInfo.Voicing.MAIN)),
					List.<PatternElement>of(element), true, 0.0, 50_000_000, 1, null);

			Assert.assertTrue("the chord gathered one note per tone",
					element.getRecorded().size() == 3);
			Assert.assertTrue("the per-note path does not memoize", features.gatherCacheSize() == 0);
			element.getRecorded().forEach(note -> Assert.assertNull(
					"a transient note's offset argument is released after dispatch",
					note.getOffsetArg()));

			plm.destroy();
		} finally {
			PatternLayerManager.enableBatched = previousBatched;
		}
	}

	/**
	 * The batched renderer's percussion path gathers fresh destinations on every tick
	 * (percussion is not memoized), so {@link BatchedPatternLayerRenderer#render} owns
	 * them and must release their offset arguments after the dispatch returns.
	 */
	@Test(timeout = 120000)
	public void batchedPercussionRenderReleasesTransientOffsetArgs() {
		boolean previousBatched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			Scale<?> scale = Scale.of(WesternChromatic.C4);
			RecordingElement element = recordingElement(
					ScaleTraversalStrategy.CHORD, List.of(0.0), 1);

			AudioSceneContext ctx = context(scale);
			ctx.setDestination(new PackedCollection(1));

			PatternLayerManager plm = new PatternLayerManager(List.of(),
					new ProjectedGenome(8).addChromosome(), 0, 4.0, true);
			BatchedPatternLayerRenderer renderer = plm.getBatchedLayerRenderer();

			renderer.render(ctx, audioContext(element.getNote(ChannelInfo.Voicing.MAIN)),
					List.<PatternElement>of(element), false, 0.0, 50_000_000, 1, null);

			Assert.assertTrue("percussion gathered one destination",
					element.getRecorded().size() == 1);
			Assert.assertTrue("percussion destinations are not memoized",
					renderer.gatherCacheSize() == 0);
			element.getRecorded().forEach(note -> Assert.assertNull(
					"a transient percussion note's offset argument is released after dispatch",
					note.getOffsetArg()));

			plm.destroy();
		} finally {
			PatternLayerManager.enableBatched = previousBatched;
		}
	}

	/**
	 * The batched renderer's melodic path memoizes its destinations, so
	 * {@link BatchedPatternLayerRenderer#render} must leave them intact after a tick —
	 * the gather cache owns them and releases them only on an epoch advance or teardown.
	 * Destroying them per tick would strand the cache's live references.
	 */
	@Test(timeout = 120000)
	public void batchedMelodicRenderRetainsMemoizedOffsetArgs() {
		boolean previousBatched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			Scale<?> scale = Scale.of(WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);
			RecordingElement element = recordingElement(
					ScaleTraversalStrategy.CHORD, List.of(0.0, 0.5, 1.0), 1);

			AudioSceneContext ctx = context(scale);
			ctx.setDestination(new PackedCollection(1));

			PatternLayerManager plm = new PatternLayerManager(List.of(),
					new ProjectedGenome(8).addChromosome(), 0, 4.0, true);
			BatchedPatternLayerRenderer renderer = plm.getBatchedLayerRenderer();

			renderer.render(ctx, audioContext(element.getNote(ChannelInfo.Voicing.MAIN)),
					List.<PatternElement>of(element), true, 0.0, 50_000_000, 1, null);

			Assert.assertTrue("the melodic gather is memoized under a single key",
					renderer.gatherCacheSize() == 1);

			List<PackedCollection> offsetArgs = element.getRecorded().stream()
					.map(RenderedNoteAudio::getOffsetArg)
					.toList();
			Assert.assertTrue("the chord gathered one note per tone", offsetArgs.size() == 3);
			offsetArgs.forEach(arg -> {
				Assert.assertNotNull("a memoized note keeps its offset argument after a tick", arg);
				Assert.assertFalse("a memoized note's offset argument is not released per tick",
						arg.isDestroyed());
			});

			plm.destroy();
			offsetArgs.forEach(arg -> Assert.assertTrue(
					"teardown releases the memoized offset arguments", arg.isDestroyed()));
		} finally {
			PatternLayerManager.enableBatched = previousBatched;
		}
	}

	/**
	 * {@link PatternFeatures#renderPerNote} gathers the notes of every element inside its
	 * protected scope, so a gather that fails partway still releases the transient notes
	 * produced by earlier elements. A recording element gathers first, then a throwing
	 * element aborts the gather; the recording element's offset arguments must be released.
	 */
	@Test(timeout = 120000)
	public void renderPerNoteReleasesTransientOffsetArgsOnPartialGatherFailure() {
		boolean previousBatched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			Scale<?> scale = Scale.of(WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);
			RecordingElement recording = recordingElement(
					ScaleTraversalStrategy.CHORD, List.of(0.0, 0.5, 1.0), 1);
			ThrowingElement throwing = new ThrowingElement(
					Map.of(ChannelInfo.Voicing.MAIN, buildNote()), 0.0);

			AudioSceneContext ctx = context(scale);
			ctx.setDestination(new PackedCollection(1));

			PatternLayerManager plm = new PatternLayerManager(List.of(),
					new ProjectedGenome(8).addChromosome(), 0, 4.0, true);
			BatchedPatternLayerRenderer features = plm.getBatchedLayerRenderer();

			boolean threw = false;
			try {
				features.renderPerNote(
						ctx, audioContext(recording.getNote(ChannelInfo.Voicing.MAIN)),
						List.<PatternElement>of(recording, throwing), true, 0.0, 50_000_000, 1, null);
			} catch (GatherFailure e) {
				threw = true;
			}
			Assert.assertTrue("the partial gather aborts with the simulated failure", threw);

			Assert.assertEquals("the recording element gathered one note per tone",
					3, recording.getRecorded().size());
			recording.getRecorded().forEach(note -> Assert.assertNull(
					"a partial gather still releases the earlier notes' offset arguments",
					note.getOffsetArg()));

			plm.destroy();
		} finally {
			PatternLayerManager.enableBatched = previousBatched;
		}
	}

	/**
	 * {@link BatchedPatternLayerRenderer#render} gathers transient percussion destinations
	 * inside its protected scope, so a gather that fails partway still releases the notes
	 * produced by earlier elements rather than leaking their offset arguments.
	 */
	@Test(timeout = 120000)
	public void batchedPercussionRenderReleasesTransientOffsetArgsOnPartialGatherFailure() {
		boolean previousBatched = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = false;

		try {
			Scale<?> scale = Scale.of(WesternChromatic.C4);
			RecordingElement recording = recordingElement(
					ScaleTraversalStrategy.CHORD, List.of(0.0), 1);
			ThrowingElement throwing = new ThrowingElement(
					Map.of(ChannelInfo.Voicing.MAIN, buildNote()), 0.0);

			AudioSceneContext ctx = context(scale);
			ctx.setDestination(new PackedCollection(1));

			PatternLayerManager plm = new PatternLayerManager(List.of(),
					new ProjectedGenome(8).addChromosome(), 0, 4.0, true);
			BatchedPatternLayerRenderer renderer = plm.getBatchedLayerRenderer();

			boolean threw = false;
			try {
				renderer.render(
						ctx, audioContext(recording.getNote(ChannelInfo.Voicing.MAIN)),
						List.<PatternElement>of(recording, throwing), false, 0.0, 50_000_000, 1, null);
			} catch (GatherFailure e) {
				threw = true;
			}
			Assert.assertTrue("the partial gather aborts with the simulated failure", threw);

			Assert.assertEquals("the recording element gathered one destination",
					1, recording.getRecorded().size());
			recording.getRecorded().forEach(note -> Assert.assertNull(
					"a partial percussion gather still releases the earlier notes' offset arguments",
					note.getOffsetArg()));

			plm.destroy();
		} finally {
			PatternLayerManager.enableBatched = previousBatched;
		}
	}
}
