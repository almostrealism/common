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
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.Scale;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.heredity.ProjectedChromosome;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.music.notes.FileNoteSource;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.NoteAudioSource;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Tests for the bookkeeping of {@link PatternSystemManager}: source lookup, pattern
 * creation from settings, seed bias balancing across a channel, and MIDI export
 * across pattern repetitions. Audio rendering is covered by
 * {@link PatternRenderTest}.
 */
public class PatternSystemManagerTest extends TestSuiteBase {

	/**
	 * Creates the given number of independent chromosomes.
	 *
	 * @param count the number of chromosomes
	 * @return the chromosomes
	 */
	static List<ProjectedChromosome> chromosomes(int count) {
		ProjectedGenome genome = new ProjectedGenome(8);
		return IntStream.range(0, count).mapToObj(i -> genome.addChromosome()).collect(Collectors.toList());
	}

	/**
	 * Creates a percussive element at the given position.
	 *
	 * @param position the position in measures
	 * @return the element
	 */
	private static PatternElement hit(double position) {
		return new PatternElement(new PatternNote(0.1, 0.5, 0.9), position);
	}

	/** Sources are looked up by choice id and flattened across choices. */
	@Test(timeout = 30000)
	public void sourcesAreFoundByChoice() {
		NoteAudioChoice kick = new NoteAudioChoice("kick");
		kick.getSources().add(new FileNoteSource("kick-a.wav"));
		kick.getSources().add(new FileNoteSource("kick-b.wav"));
		NoteAudioChoice snare = new NoteAudioChoice("snare");
		snare.getSources().add(new FileNoteSource("snare.wav"));

		PatternSystemManager psm = new PatternSystemManager(List.of(kick, snare), chromosomes(1));

		Assert.assertEquals(List.of(kick, snare), psm.getChoices());
		Assert.assertSame(kick.getSources(), psm.getSource(kick.getId()));
		Assert.assertSame(snare.getSources(), psm.getSource(snare.getId()));
		Assert.assertNull(psm.getSource("unknown"));
		Assert.assertEquals(3, psm.getAllSources().size());

		List<Double> progress = new ArrayList<>();
		psm.setTree(null, progress::add);
		Assert.assertEquals("progress starts at zero and advances once per source",
				List.of(0.0, 1.0 / 3, 2.0 / 3, 1.0), progress);
	}

	/**
	 * Seeds the given manager's note-audio cache with a single owned entry, so that a
	 * later teardown has something to release.
	 *
	 * @param manager the manager whose cache to seed
	 * @return the audio placed in the cache
	 */
	private PackedCollection seedCache(PatternLayerManager manager) {
		PackedCollection audio = new PackedCollection(shape(16).traverseEach());
		manager.getNoteAudioCache().put(0, null, audio);
		Assert.assertEquals(1, manager.getNoteAudioCache().size());
		return audio;
	}

	/**
	 * {@code clear()} destroys each dropped pattern's note-audio cache rather than
	 * leaving its native memory reachable only through the garbage collector.
	 */
	@Test(timeout = 30000)
	public void clearReleasesDroppedPatternCaches() {
		PatternSystemManager psm = new PatternSystemManager(chromosomes(2));
		PatternLayerManager first = psm.addPattern(0, 2.0, false);
		PatternLayerManager second = psm.addPattern(1, 2.0, false);
		PackedCollection firstAudio = seedCache(first);
		PackedCollection secondAudio = seedCache(second);

		psm.clear();

		Assert.assertTrue(psm.getPatterns().isEmpty());
		Assert.assertEquals("the dropped manager's cache is emptied", 0, first.getNoteAudioCache().size());
		Assert.assertEquals(0, second.getNoteAudioCache().size());
		Assert.assertTrue("the cached audio is released, not leaked", firstAudio.isDestroyed());
		Assert.assertTrue(secondAudio.isDestroyed());
	}

	/**
	 * Reloading settings into a live manager releases the caches of the patterns it
	 * replaces, so their native memory is not stranded beyond the reach of
	 * {@link PatternSystemManager#destroy()}.
	 */
	@Test(timeout = 30000)
	public void setSettingsReleasesReplacedPatternCaches() {
		PatternSystemManager psm = new PatternSystemManager(chromosomes(2));
		PatternLayerManager original = psm.addPattern(0, 2.0, false);
		PackedCollection originalAudio = seedCache(original);

		PatternSystemManager.Settings settings = new PatternSystemManager.Settings();
		settings.setPatterns(new ArrayList<>());
		psm.setSettings(settings);

		Assert.assertTrue(psm.getPatterns().isEmpty());
		Assert.assertEquals("the replaced manager's cache is emptied", 0, original.getNoteAudioCache().size());
		Assert.assertTrue("the replaced manager's cached audio is released", originalAudio.isDestroyed());
	}

	/**
	 * {@code destroy()} empties the pattern list, releasing every manager's cache, and
	 * is idempotent across repeated teardown.
	 */
	@Test(timeout = 30000)
	public void destroyReleasesAllPatternCaches() {
		PatternSystemManager psm = new PatternSystemManager(chromosomes(1));
		PatternLayerManager pattern = psm.addPattern(0, 2.0, false);
		PackedCollection audio = seedCache(pattern);

		psm.destroy();

		Assert.assertTrue(psm.getPatterns().isEmpty());
		Assert.assertEquals(0, pattern.getNoteAudioCache().size());
		Assert.assertTrue(audio.isDestroyed());

		psm.destroy();
		Assert.assertTrue("a repeated teardown is a no-op", psm.getPatterns().isEmpty());
	}

	/** Pattern elements are merged across patterns by the choice that owns them. */
	@Test(timeout = 30000)
	public void patternElementsAreMergedByChoice() {
		PatternSystemManager psm = new PatternSystemManager(chromosomes(2));
		NoteAudioChoice shared = new NoteAudioChoice("shared");

		psm.addPattern(0, 2.0, false).setExplicitElements(shared, List.of(hit(0.0), hit(1.5)));
		psm.addPattern(1, 2.0, false).setExplicitElements(shared, List.of(hit(0.5)));

		Assert.assertEquals(2, psm.getPatterns().size());
		Assert.assertEquals(3, psm.getPatternElements(0.0, 2.0).get(shared).size());
		Assert.assertEquals(2, psm.getPatternElements(0.0, 1.0).get(shared).size());

		psm.clear();
		Assert.assertTrue(psm.getPatterns().isEmpty());
		Assert.assertTrue(psm.getPatternElements(0.0, 2.0).isEmpty());
	}

	/** Settings capture every pattern and recreate them, in order, on another manager. */
	@Test(timeout = 60000)
	public void settingsRecreatePatterns() {
		PatternSystemManager source = new PatternSystemManager(chromosomes(2));
		source.addPattern(1, 4.0, false);
		PatternLayerManager melodic = source.addPattern(3, 8.0, true);
		melodic.setScaleTraversalStrategy(ScaleTraversalStrategy.SEQUENCE);
		melodic.setScaleTraversalDepth(3);

		PatternSystemManager.Settings settings = source.getSettings();
		Assert.assertEquals(2, settings.getPatterns().size());

		PatternSystemManager target = new PatternSystemManager(chromosomes(2));
		target.addPattern(7, 1.0, false);
		target.setSettings(settings);

		List<PatternLayerManager> patterns = target.getPatterns();
		Assert.assertEquals(2, patterns.size());
		Assert.assertEquals(1, patterns.get(0).getChannel());
		Assert.assertEquals(4.0, patterns.get(0).getDuration(), 0.0);
		Assert.assertFalse(patterns.get(0).isMelodic());
		Assert.assertEquals(3, patterns.get(1).getChannel());
		Assert.assertEquals(8.0, patterns.get(1).getDuration(), 0.0);
		Assert.assertTrue(patterns.get(1).isMelodic());
		Assert.assertEquals(ScaleTraversalStrategy.SEQUENCE, patterns.get(1).getScaleTraversalStrategy());
		Assert.assertEquals(3, patterns.get(1).getScaleTraversalDepth());

		PatternSystemManager.Settings replaced = new PatternSystemManager.Settings();
		replaced.setPatterns(new ArrayList<>());
		target.setSettings(replaced);
		Assert.assertTrue(target.getPatterns().isEmpty());
	}

	/**
	 * Default settings assign melodic content, traversal strategy and depth per
	 * channel, and only give layers to the active patterns of each channel.
	 */
	@Test(timeout = 30000)
	public void defaultSettingsFollowChannelConventions() {
		PatternSystemManager.Settings settings = PatternSystemManager.Settings.defaultSettings(
				6, 2, c -> 1, c -> c + 1, c -> 0.25 * c, c -> 4 * (c + 1));

		List<PatternLayerManager.Settings> patterns = settings.getPatterns();
		Assert.assertEquals(12, patterns.size());

		for (int i = 0; i < patterns.size(); i++) {
			PatternLayerManager.Settings p = patterns.get(i);
			int c = i / 2;
			boolean melodic = c > 1 && c != 5;

			Assert.assertEquals(c, p.getChannel());
			Assert.assertEquals(4.0 * (c + 1), p.getDuration(), 0.0);
			Assert.assertEquals("channel " + c, melodic, p.isMelodic());
			Assert.assertEquals((c == 2 || c == 4) ? ScaleTraversalStrategy.SEQUENCE : ScaleTraversalStrategy.CHORD,
					p.getScaleTraversalStrategy());
			Assert.assertEquals(melodic ? 5 : 1, p.getScaleTraversalDepth());
			Assert.assertEquals(0.25 * c, p.getMinLayerScale(), 0.0);
			Assert.assertEquals("only the first pattern of each channel is active",
					i % 2 == 0 ? c + 1 : 0, p.getLayerCount());
			Assert.assertNotNull(p.getFactorySelection());
			Assert.assertNotNull(p.getActiveSelection());
		}
	}

	/**
	 * Refreshing balances seed bias per channel: the more patterns with layers a
	 * channel has, the lower the bias adjustment of every pattern on it.
	 */
	@Test(timeout = 120000)
	public void refreshBalancesSeedBiasPerChannel() {
		PatternSystemManager psm = new PatternSystemManager(chromosomes(3));
		PatternLayerManager active = psm.addPattern(0, 2.0, false);
		PatternLayerManager inactive = psm.addPattern(0, 2.0, false);
		PatternLayerManager alone = psm.addPattern(1, 2.0, false);
		active.setLayerCount(1);

		psm.refreshParameters();

		Assert.assertEquals(Math.exp(0.75), active.getSeedBiasAdjustment(), 1e-12);
		Assert.assertEquals(Math.exp(0.75), inactive.getSeedBiasAdjustment(), 1e-12);
		Assert.assertEquals(Math.exp(1.0), alone.getSeedBiasAdjustment(), 1e-12);
		Assert.assertEquals("refreshing keeps the requested layers", 1, active.depth());
	}

	/**
	 * MIDI export repeats each pattern across the requested range, can be restricted
	 * to selected patterns, and skips patterns without a duration.
	 */
	@Test(timeout = 30000)
	public void midiExportRepeatsPatterns() {
		PatternSystemManager psm = new PatternSystemManager(chromosomes(3));
		NoteAudioChoice choice = new NoteAudioChoice("hits");
		psm.addPattern(0, 2.0, false).setExplicitElements(choice, List.of(hit(0.5)));
		psm.addPattern(1, 4.0, false).setExplicitElements(choice, List.of(hit(1.0)));
		psm.addPattern(2, 0.0, false).setExplicitElements(choice, List.of(hit(0.0)));

		int measureFrames = 2 * OutputLine.sampleRate;
		AudioSceneContext context = new AudioSceneContext();
		context.setMeasures(8);
		context.setFrames(8 * measureFrames);
		context.setFrameForPosition(pos -> (int) (pos * measureFrames));
		context.setTimeForDuration(d -> d * 2.0);
		context.setScaleForPosition(pos -> Scale.of(WesternChromatic.C4));

		Assert.assertEquals("measures 0.5, 1, 2.5, 4.5, 5, 6.5 at 200 ticks per measure",
				List.of(100L, 200L, 500L, 900L, 1000L, 1300L), onsets(psm.toMidiEvents(context)));
		Assert.assertEquals(List.of(900L, 1000L, 1300L), onsets(psm.toMidiEvents(context, 4.0, 8.0)));
		Assert.assertEquals(List.of(200L, 1000L), onsets(psm.toMidiEvents(context, 0.0, 8.0,
				p -> p.getChannel() == 1)));
		Assert.assertTrue(psm.toMidiEvents(context, 0.0, 8.0, p -> false).isEmpty());
	}

	/**
	 * Returns the onset of each event, in order.
	 *
	 * @param events the events
	 * @return their onsets
	 */
	private static List<Long> onsets(List<MidiNoteEvent> events) {
		return events.stream().map(MidiNoteEvent::getOnset).collect(Collectors.toList());
	}

	/** A choice's sources are the ones the manager reports and retunes. */
	@Test(timeout = 30000)
	public void choicesAreShared() {
		NoteAudioChoice choice = new NoteAudioChoice("tuned");
		NoteAudioSource source = new FileNoteSource("tuned.wav");
		choice.getSources().add(source);

		List<NoteAudioChoice> choices = new ArrayList<>(List.of(choice));
		PatternSystemManager psm = new PatternSystemManager(choices, chromosomes(1));
		psm.setTuning(new DefaultKeyboardTuning());

		Assert.assertSame(choices, psm.getChoices());
		Assert.assertEquals(List.of(source), psm.getAllSources());
	}
}
