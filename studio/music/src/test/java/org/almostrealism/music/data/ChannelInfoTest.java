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

package org.almostrealism.music.data;

import org.almostrealism.music.data.ChannelInfo.StereoChannel;
import org.almostrealism.music.data.ChannelInfo.Type;
import org.almostrealism.music.data.ChannelInfo.Voicing;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the query predicates of {@link ChannelInfo}. Callers such as
 * {@code AudioSceneContext.includesChannel} and {@code MultiChannelAudioOutput}
 * ask a {@link ChannelInfo} whether it is on a given pattern channel, stereo
 * channel, or voicing rather than reading its fields directly, so these
 * predicates define the routing contract and must read only the matching field.
 */
public class ChannelInfoTest extends TestSuiteBase {

	/** {@link ChannelInfo#matches(int)} is true only for the exact pattern channel. */
	@Test(timeout = 10000)
	public void matchesPatternChannel() {
		ChannelInfo info = new ChannelInfo(3, Type.PATTERN, Voicing.MAIN, StereoChannel.LEFT);

		Assert.assertTrue(info.matches(3));
		Assert.assertFalse(info.matches(2));
		Assert.assertFalse(info.matches(-1));
		Assert.assertFalse(info.matches(0));
	}

	/** {@link ChannelInfo#matches(int)} reads only the pattern channel, independent of the stereo channel. */
	@Test(timeout = 10000)
	public void matchesPatternChannelIgnoresOtherFields() {
		ChannelInfo left = new ChannelInfo(5, StereoChannel.LEFT);
		ChannelInfo right = new ChannelInfo(5, StereoChannel.RIGHT);

		Assert.assertTrue(left.matches(5));
		Assert.assertTrue(right.matches(5));
	}

	/** {@link ChannelInfo#matches(StereoChannel)} is true only for the exact stereo channel. */
	@Test(timeout = 10000)
	public void matchesStereoChannel() {
		ChannelInfo left = new ChannelInfo(0, StereoChannel.LEFT);

		Assert.assertTrue(left.matches(StereoChannel.LEFT));
		Assert.assertFalse(left.matches(StereoChannel.RIGHT));
	}

	/** A {@link ChannelInfo} with no stereo channel does not match either LEFT or RIGHT, but matches null. */
	@Test(timeout = 10000)
	public void matchesStereoChannelHandlesNull() {
		ChannelInfo unspecified = new ChannelInfo(0);

		Assert.assertFalse(unspecified.matches(StereoChannel.LEFT));
		Assert.assertFalse(unspecified.matches(StereoChannel.RIGHT));
		Assert.assertTrue(unspecified.matches((StereoChannel) null));
	}

	/** {@link ChannelInfo#matches(Voicing)} is true only for the exact voicing. */
	@Test(timeout = 10000)
	public void matchesVoicing() {
		ChannelInfo wet = new ChannelInfo(0, Voicing.WET, StereoChannel.LEFT);

		Assert.assertTrue(wet.matches(Voicing.WET));
		Assert.assertFalse(wet.matches(Voicing.MAIN));
	}

	/** The pattern-channel-only convenience constructor defaults to a MAIN voicing. */
	@Test(timeout = 10000)
	public void defaultVoicingIsMain() {
		ChannelInfo info = new ChannelInfo(1, StereoChannel.LEFT);

		Assert.assertTrue(info.matches(Voicing.MAIN));
		Assert.assertFalse(info.matches(Voicing.WET));
	}
}
