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

import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the value semantics of {@link ElementVoicingDetails}, which is used as
 * a key when note audio is cached per voicing.
 */
public class ElementVoicingDetailsTest extends TestSuiteBase {

	/**
	 * Creates a melodic MAIN/LEFT voicing targeting C4.
	 *
	 * @return the details
	 */
	private static ElementVoicingDetails reference() {
		return new ElementVoicingDetails(ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
				true, WesternChromatic.C4, 0.5, 0.75);
	}

	/** Convenience constructors default to an unpitched main voicing at position zero. */
	@Test(timeout = 10000)
	public void constructorsApplyDefaults() {
		ElementVoicingDetails defaults = new ElementVoicingDetails();
		Assert.assertEquals(ChannelInfo.Voicing.MAIN, defaults.getVoicing());
		Assert.assertNull(defaults.getStereoChannel());
		Assert.assertFalse(defaults.isMelodic());
		Assert.assertNull(defaults.getTarget());
		Assert.assertEquals(0.0, defaults.getPosition(), 0.0);
		Assert.assertEquals(0.0, defaults.getNextNotePosition(), 0.0);

		ElementVoicingDetails wet = new ElementVoicingDetails(ChannelInfo.Voicing.WET, true, WesternChromatic.A0);
		Assert.assertEquals(ChannelInfo.Voicing.WET, wet.getVoicing());
		Assert.assertTrue(wet.isMelodic());
		Assert.assertEquals(WesternChromatic.A0, wet.getTarget());
	}

	/** Equal details compare equal and share a hash code. */
	@Test(timeout = 10000)
	public void equalDetailsShareHash() {
		ElementVoicingDetails a = reference();
		ElementVoicingDetails b = reference();

		Assert.assertEquals(a, b);
		Assert.assertEquals(a.hashCode(), b.hashCode());
		Assert.assertNotEquals(a, "not a voicing");
		Assert.assertNotEquals(a, null);
	}

	/** Every identity field distinguishes two details. */
	@Test(timeout = 10000)
	public void identityFieldsDistinguishDetails() {
		ElementVoicingDetails voicing = reference();
		voicing.setVoicing(ChannelInfo.Voicing.WET);
		Assert.assertNotEquals(reference(), voicing);

		ElementVoicingDetails melodic = reference();
		melodic.setMelodic(false);
		Assert.assertNotEquals(reference(), melodic);

		ElementVoicingDetails target = reference();
		target.setTarget(WesternChromatic.D4);
		Assert.assertNotEquals(reference(), target);

		ElementVoicingDetails position = reference();
		position.setPosition(0.25);
		Assert.assertNotEquals(reference(), position);

		ElementVoicingDetails next = reference();
		next.setNextNotePosition(1.0);
		Assert.assertNotEquals(reference(), next);
	}

	/**
	 * The stereo channel is deliberately excluded from {@link ElementVoicingDetails}
	 * equality and hash code. The LEFT and RIGHT renders of a note are kept distinct
	 * in {@link org.almostrealism.music.pattern.NoteAudioCache} not by these details
	 * but by the cache identity, which {@code ScaleTraversalStrategy.createRenderedNote}
	 * builds as {@code (element, details, stereoChannel)} — the channel is listed
	 * there separately precisely because it does not participate in this equality.
	 */
	@Test(timeout = 10000)
	public void stereoChannelDoesNotAffectIdentity() {
		ElementVoicingDetails right = reference();
		right.setStereoChannel(ChannelInfo.StereoChannel.RIGHT);

		Assert.assertEquals(ChannelInfo.StereoChannel.RIGHT, right.getStereoChannel());
		Assert.assertEquals(reference(), right);
		Assert.assertEquals(reference().hashCode(), right.hashCode());
	}
}
