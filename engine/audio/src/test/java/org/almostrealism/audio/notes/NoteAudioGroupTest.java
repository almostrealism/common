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

package org.almostrealism.audio.notes;

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Random;

/**
 * Tests for {@link NoteAudioGroup}: nearest-member selection (including the
 * edge cases in the use-design memo §3.3) and the backward-compatibility anchor
 * that a one-member group renders byte-identically to the bare sample.
 *
 * @see NoteAudioGroup
 * @see NoteAudioProvider
 */
public class NoteAudioGroupTest extends TestSuiteBase {

	/** Number of samples in each generated in-memory source buffer. */
	private static final int SOURCE_LENGTH = 2048;

	/** Shared tuning used by every member so ratios are comparable. */
	private final KeyboardTuning tuning = new DefaultKeyboardTuning();

	/**
	 * Creates a reproducible random source buffer in [-1, 1] for the given seed.
	 */
	private PackedCollection randomSource(long seed) {
		Random rng = new Random(seed);
		double[] data = new double[SOURCE_LENGTH];
		for (int i = 0; i < SOURCE_LENGTH; i++) {
			data[i] = rng.nextDouble() * 2.0 - 1.0;
		}
		PackedCollection c = new PackedCollection(SOURCE_LENGTH);
		c.setMem(data);
		return c;
	}

	/**
	 * Builds a tuned member with the given captured root over a fresh random
	 * source. The seed defaults to the root's position so distinct roots get
	 * distinct audio.
	 */
	private NoteAudioProvider member(KeyPosition<?> root) {
		PackedCollection source = randomSource(root.position() + 1L);
		NoteAudioProvider note = NoteAudioProvider.create(() -> source, root);
		note.setTuning(tuning);
		return note;
	}

	/** Asserts two collections are element-for-element identical (byte-identical render). */
	private void assertBytesIdentical(PackedCollection expected, PackedCollection actual) {
		Assert.assertEquals("Frame count must match", expected.getMemLength(), actual.getMemLength());
		for (int i = 0; i < expected.getMemLength(); i++) {
			Assert.assertEquals("Sample " + i + " must match exactly",
					expected.toDouble(i), actual.toDouble(i), 0.0);
		}
	}

	/**
	 * Backward-compatibility anchor: a one-member group renders byte-identically
	 * to the bare sample for exact, pitch-up, and pitch-down targets — same field,
	 * same formula, same cache.
	 */
	@Test(timeout = 120000)
	public void oneMemberGroupMatchesBareSample() {
		PackedCollection source = randomSource(7);

		NoteAudioProvider bare = NoteAudioProvider.create(() -> source, WesternChromatic.C4);
		bare.setTuning(tuning);

		NoteAudioProvider groupMember = NoteAudioProvider.create(() -> source, WesternChromatic.C4);
		groupMember.setTuning(tuning);
		NoteAudioGroup group = new NoteAudioGroup(List.of(groupMember));

		for (KeyPosition<?> target :
				List.of(WesternChromatic.C4, WesternChromatic.G4, WesternChromatic.C3)) {
			PackedCollection expected = bare.getAudio(target, 0).evaluate();
			PackedCollection actual = group.getAudio(target, 0).evaluate();
			assertBytesIdentical(expected, actual);
		}
	}

	/**
	 * An exact chromatic hit selects the matching member and delivers it with a
	 * residual ratio of 1.0 — a pure passthrough of the raw channel data, no
	 * resampling.
	 */
	@Test(timeout = 120000)
	public void exactChromaticHitIsPassthrough() {
		NoteAudioProvider c2 = member(WesternChromatic.C2);
		NoteAudioProvider c4 = member(WesternChromatic.C4);
		NoteAudioProvider c6 = member(WesternChromatic.C6);
		NoteAudioGroup group = new NoteAudioGroup(List.of(c2, c4, c6));

		Assert.assertSame("Exact hit must select the matching member",
				c4, group.nearest(WesternChromatic.C4));

		PackedCollection passthrough =
				c4.getProvider().getChannelData(0, 1.0, OutputLine.sampleRate);
		PackedCollection delivered = group.getAudio(WesternChromatic.C4, 0).evaluate();
		assertBytesIdentical(passthrough, delivered);
	}

	/** A target above the captured range resolves to the highest member. */
	@Test(timeout = 30000)
	public void targetAboveRangeUsesHighestMember() {
		NoteAudioProvider c2 = member(WesternChromatic.C2);
		NoteAudioProvider c4 = member(WesternChromatic.C4);
		NoteAudioGroup group = new NoteAudioGroup(List.of(c2, c4));

		Assert.assertSame("Above-range target must use the highest member",
				c4, group.nearest(WesternChromatic.C7));
	}

	/** A target below the captured range resolves to the lowest member. */
	@Test(timeout = 30000)
	public void targetBelowRangeUsesLowestMember() {
		NoteAudioProvider c2 = member(WesternChromatic.C2);
		NoteAudioProvider c4 = member(WesternChromatic.C4);
		NoteAudioGroup group = new NoteAudioGroup(List.of(c2, c4));

		Assert.assertSame("Below-range target must use the lowest member",
				c2, group.nearest(WesternChromatic.A0));
	}

	/**
	 * An equidistant target deterministically prefers the lower member (shift up),
	 * regardless of member order.
	 */
	@Test(timeout = 30000)
	public void equidistantPrefersLowerMember() {
		NoteAudioProvider c4 = member(WesternChromatic.C4);   // position 39
		NoteAudioProvider d4 = member(WesternChromatic.D4);   // position 41

		// CS4 (position 40) is exactly between C4 and D4.
		Assert.assertSame("Equidistant must prefer the lower member",
				c4, new NoteAudioGroup(List.of(c4, d4)).nearest(WesternChromatic.CS4));
		Assert.assertSame("Tie-break must be order-independent",
				c4, new NoteAudioGroup(List.of(d4, c4)).nearest(WesternChromatic.CS4));
	}

	/**
	 * A {@code null} target (no specific pitch requested) selects the lowest
	 * pitched member. This guards the {@link NoteAudioGroup#nearest(KeyPosition)}
	 * null-target sentinel against integer overflow: the lowest member here is not
	 * at position 0, so a sentinel that overflowed would wrongly favour the highest
	 * member instead.
	 */
	@Test(timeout = 30000)
	public void nullTargetSelectsLowestMember() {
		NoteAudioProvider c4 = member(WesternChromatic.C4);   // position 39
		NoteAudioProvider c6 = member(WesternChromatic.C6);   // position 63

		Assert.assertSame("Null target must select the lowest pitched member",
				c4, new NoteAudioGroup(List.of(c4, c6)).nearest(null));
		Assert.assertSame("Null-target selection must be order-independent",
				c4, new NoteAudioGroup(List.of(c6, c4)).nearest(null));
	}

	/** A pitchless member is excluded from selection but does not invalidate a group with a pitched member. */
	@Test(timeout = 30000)
	public void pitchlessMemberExcluded() {
		PackedCollection source = randomSource(11);
		NoteAudioProvider pitchless = NoteAudioProvider.create(() -> source, KeyPosition.none());
		pitchless.setTuning(tuning);
		NoteAudioProvider c4 = member(WesternChromatic.C4);

		NoteAudioGroup group = new NoteAudioGroup(List.of(pitchless, c4));

		Assert.assertTrue("A group with one pitched member is usable", group.isValid());
		// A0 (position 0) is nearer the pitchless sentinel (position -1) than to C4
		// (position 39); the pitchless member must still never be chosen.
		Assert.assertSame("Pitchless member must be excluded from selection",
				c4, group.nearest(WesternChromatic.A0));
	}

	/** A group with no usable pitched members is invalid and yields no audio. */
	@Test(timeout = 30000)
	public void noUsablePitchedMembersIsGraceful() {
		PackedCollection source = randomSource(13);
		NoteAudioProvider pitchless = NoteAudioProvider.create(() -> source, KeyPosition.none());
		pitchless.setTuning(tuning);
		NoteAudioGroup group = new NoteAudioGroup(List.of(pitchless));

		Assert.assertFalse("Pitchless-only group must be invalid", group.isValid());
		Assert.assertNull("No nearest member exists", group.nearest(WesternChromatic.C4));
		Assert.assertEquals("Duration falls back to 0", 0.0, group.getDuration(WesternChromatic.C4), 0.0);

		Assert.assertFalse("Empty group must be invalid", new NoteAudioGroup().isValid());

		try {
			group.getAudio(WesternChromatic.C4, 0);
			Assert.fail("getAudio on an unusable group must throw");
		} catch (IllegalStateException expected) {
			// expected: an unusable group is filtered before render, so this path
			// is defensive only.
		}
	}
}
