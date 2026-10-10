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

import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.Scale;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.audio.tone.WesternScales;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the settings, region layout and chord selection contract of
 * {@link ChordProgressionManager}. Region lengths and chords are chosen by random
 * selection functions, so these tests assert the invariants that hold for every
 * choice rather than specific chords.
 */
public class ChordProgressionManagerSettingsTest extends TestSuiteBase {

	/**
	 * Returns the notes of a scale in order.
	 *
	 * @param scale the scale
	 * @return its notes
	 */
	private static List<KeyPosition<?>> notes(Scale<?> scale) {
		List<KeyPosition<?>> notes = new ArrayList<>();
		scale.forEach(notes::add);
		return notes;
	}

	/** The scale type reported by the settings is derived from the third of the key. */
	@Test(timeout = 30000)
	public void settingsReportKeyAndDimensions() {
		ChordProgressionManager progression = new ChordProgressionManager(
				new ProjectedGenome(4).addChromosome(), WesternScales.minor(WesternChromatic.G1, 1));
		progression.setSize(8);
		progression.setDuration(16);
		progression.setChordDepth(3);

		ChordProgressionManager.Settings settings = progression.getSettings();
		Assert.assertEquals(ChordProgressionManager.ScaleType.MINOR, settings.getScaleType());
		Assert.assertEquals(WesternChromatic.G1, settings.getRoot());
		Assert.assertEquals(8, settings.getSize());
		Assert.assertEquals(16.0, settings.getDuration(), 0.0);
		Assert.assertEquals(3, settings.getChordDepth());
		Assert.assertEquals(ChordProgressionManager.MAX_SIZE, settings.getRegionLengthSelection().size());
		Assert.assertEquals(ChordProgressionManager.MAX_SIZE, settings.getChordSelection().size());

		progression.setKey(WesternScales.major(WesternChromatic.E1, 1));
		Assert.assertEquals(ChordProgressionManager.ScaleType.MAJOR, progression.getSettings().getScaleType());
		Assert.assertEquals(WesternChromatic.E1, progression.getSettings().getRoot());
	}

	/** Applying settings rebuilds the key from its root and type and copies every dimension. */
	@Test(timeout = 30000)
	public void settingsAreApplied() {
		ChordProgressionManager progression = new ChordProgressionManager(4);
		ChordProgressionManager.Settings settings = ChordProgressionManager.Settings.defaultSettings();
		progression.setSettings(settings);

		Assert.assertEquals(notes(WesternScales.minor(WesternChromatic.D1, 1)), notes(progression.getKey()));
		Assert.assertEquals(16, progression.getSize());
		Assert.assertEquals(8.0, progression.getDuration(), 0.0);
		Assert.assertEquals(5, progression.getChordDepth());
		Assert.assertSame(settings.getRegionLengthSelection(), progression.getRegionLengthSelection());
		Assert.assertSame(settings.getChordSelection(), progression.getChordSelection());

		settings.setScaleType(ChordProgressionManager.ScaleType.MAJOR);
		settings.setRoot(WesternChromatic.F1);
		progression.setSettings(settings);
		Assert.assertEquals(notes(WesternScales.major(WesternChromatic.F1, 1)), notes(progression.getKey()));

		Scale<?> key = progression.getKey();
		settings.setRoot(null);
		settings.setSize(4);
		progression.setSettings(settings);
		Assert.assertSame("without a root the key is kept", key, progression.getKey());
		Assert.assertEquals(4, progression.getSize());
	}

	/** The progression size is bounded by the number of selection functions. */
	@Test(timeout = 30000)
	public void sizeIsBounded() {
		ChordProgressionManager progression = new ChordProgressionManager(4);
		progression.setSize(ChordProgressionManager.MAX_SIZE);
		Assert.assertEquals(ChordProgressionManager.MAX_SIZE, progression.getSize());

		try {
			progression.setSize(ChordProgressionManager.MAX_SIZE + 1);
			Assert.fail("sizes beyond the maximum are rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertEquals(ChordProgressionManager.MAX_SIZE, progression.getSize());
		}
	}

	/** A region contains the half-open interval from its start for its length. */
	@Test(timeout = 30000)
	public void regionIsHalfOpen() {
		Scale<WesternChromatic> scale = Scale.of(WesternChromatic.C1);
		ChordProgressionManager.Region region = new ChordProgressionManager.Region(2.0, 4.0, scale);

		Assert.assertSame(scale, region.getScale());
		Assert.assertTrue(region.contains(2.0));
		Assert.assertTrue(region.contains(5.99));
		Assert.assertFalse(region.contains(6.0));
		Assert.assertFalse(region.contains(1.99));
	}

	/**
	 * After refreshing, regions tile the progression with power-of-two multiples of
	 * {@code duration / size}, and every position resolves to a chord of
	 * {@code chordDepth} distinct notes drawn from the key. Positions wrap around
	 * the progression duration.
	 */
	@Test(timeout = 60000)
	public void refreshedRegionsTileTheProgression() {
		ProjectedGenome genome = new ProjectedGenome(4);
		Scale<WesternChromatic> key = WesternScales.major(WesternChromatic.C1, 1);
		ChordProgressionManager progression = new ChordProgressionManager(genome.addChromosome(), key);
		progression.setSize(8);
		progression.setDuration(16);
		progression.setChordDepth(3);

		for (int attempt = 0; attempt < 3; attempt++) {
			genome.assignTo(new PackedCollection(4).fill(0.2 * attempt, 0.5, 0.9 - 0.3 * attempt, 0.1));
			progression.refreshParameters();

			String regions = progression.getRegionString();
			int covered = 0;
			int last = 0;
			for (String segment : regions.split("(?=X)")) {
				Assert.assertTrue("segment " + segment + " in " + regions,
						segment.length() == 2 || segment.length() == 4 || segment.length() == 8);
				covered += segment.length();
				last = segment.length();
			}

			Assert.assertTrue("regions must cover the duration: " + regions, covered >= 16);
			Assert.assertTrue("no region starts after the duration: " + regions, covered - last < 16);

			List<KeyPosition<?>> keyNotes = notes(key);
			for (double position = 0.0; position < 16.0; position += 0.5) {
				List<KeyPosition<?>> chord = notes(progression.forPosition(position));
				Assert.assertEquals(3, chord.size());
				Assert.assertEquals("chord notes are distinct", 3, chord.stream().distinct().count());
				Assert.assertTrue(keyNotes.containsAll(chord));
				Assert.assertEquals(chord, notes(progression.forPosition(position + 16.0)));
			}
		}
	}

	/** A position before the start of the progression belongs to no region and falls back to the key. */
	@Test(timeout = 60000)
	public void positionOutsideRegionsFallsBackToKey() {
		Scale<WesternChromatic> key = WesternScales.minor(WesternChromatic.A1, 1);
		ChordProgressionManager progression = new ChordProgressionManager(
				new ProjectedGenome(4).addChromosome(), key);
		progression.setSize(4);
		progression.setDuration(4);
		progression.refreshParameters();

		Assert.assertSame(key, progression.forPosition(-1.0));
	}
}
