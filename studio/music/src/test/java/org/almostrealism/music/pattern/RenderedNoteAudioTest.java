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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the lifecycle contract of {@link RenderedNoteAudio}.
 */
public class RenderedNoteAudioTest extends TestSuiteBase {

	/** A rendered note without a producer factory reports the missing setup rather than returning null. */
	@Test(timeout = 10000)
	public void renderedNoteRequiresProducerFactory() {
		RenderedNoteAudio note = new RenderedNoteAudio(128, 512);
		Assert.assertEquals(128, note.getOffset());
		Assert.assertEquals(512, note.getExpectedFrameCount());
		Assert.assertNull(note.getOffsetArg());
		Assert.assertNull(note.getBatchedInputs());
		Assert.assertNull("cache identity defaults to null", note.getCacheIdentity());

		try {
			note.getProducer(64);
			Assert.fail("a producer cannot be created without a factory");
		} catch (IllegalStateException e) {
			Assert.assertTrue(e.getMessage().contains("No producer factory"));
		}

		int[] requested = { 0 };
		PackedCollection audio = new PackedCollection(4);
		note.setProducerFactory(frames -> {
			requested[0] = frames;
			return cp(audio);
		});
		note.setOffset(256);
		note.setExpectedFrameCount(32);
		note.setOffsetArg(audio);

		Assert.assertNotNull(note.getProducer(64));
		Assert.assertEquals("the requested frame count is passed to the factory", 64, requested[0]);
		Assert.assertEquals(256, note.getOffset());
		Assert.assertEquals(32, note.getExpectedFrameCount());
		Assert.assertSame(audio, note.getOffsetArg());

		RenderedNoteAudio.Identity identity = RenderedNoteAudio.Identity.of(new PatternElement(),
				new ElementVoicingDetails());
		note.setCacheIdentity(identity);
		Assert.assertSame(identity, note.getCacheIdentity());
	}

	/**
	 * A note identity compares its element by instance, its voicing details by
	 * value, and its stereo channel separately, since the details' own equality
	 * ignores the channel.
	 */
	@Test(timeout = 10000)
	public void identityDistinguishesElementDetailsAndChannel() {
		PatternElement element = new PatternElement();
		ElementVoicingDetails left = new ElementVoicingDetails(ChannelInfo.Voicing.MAIN,
				ChannelInfo.StereoChannel.LEFT, false, null, 1.0, 2.0);
		ElementVoicingDetails leftAgain = new ElementVoicingDetails(ChannelInfo.Voicing.MAIN,
				ChannelInfo.StereoChannel.LEFT, false, null, 1.0, 2.0);
		ElementVoicingDetails right = new ElementVoicingDetails(ChannelInfo.Voicing.MAIN,
				ChannelInfo.StereoChannel.RIGHT, false, null, 1.0, 2.0);
		ElementVoicingDetails wet = new ElementVoicingDetails(ChannelInfo.Voicing.WET,
				ChannelInfo.StereoChannel.LEFT, false, null, 1.0, 2.0);

		RenderedNoteAudio.Identity identity = RenderedNoteAudio.Identity.of(element, left);
		Assert.assertEquals(ChannelInfo.StereoChannel.LEFT, identity.stereoChannel());
		Assert.assertSame(element, identity.element());
		Assert.assertEquals("the snapshot equals the details it was taken from", left, identity.details());
		Assert.assertNotSame("the identity snapshots the details rather than aliasing them",
				left, identity.details());

		RenderedNoteAudio.Identity same = RenderedNoteAudio.Identity.of(element, leftAgain);
		Assert.assertEquals("equal details of the same element give an equal identity", identity, same);
		Assert.assertEquals(identity.hashCode(), same.hashCode());

		Assert.assertEquals("voicing details ignore the stereo channel", left, right);
		Assert.assertNotEquals(identity, RenderedNoteAudio.Identity.of(element, right));
		Assert.assertNotEquals(identity, RenderedNoteAudio.Identity.of(element, wet));
		Assert.assertNotEquals("an equal-valued but distinct element is a different note",
				identity, RenderedNoteAudio.Identity.of(new PatternElement(), leftAgain));

		// The snapshot isolates the key from later mutation of the caller's details, so
		// the key's value and hash stay stable for the lifetime of a cache entry.
		int before = identity.hashCode();
		left.setPosition(99.0);
		Assert.assertEquals("mutating the original details does not change the snapshot",
				1.0, identity.details().getPosition(), 0.0);
		Assert.assertEquals("the snapshot keeps a stable hash after a mutation of the original",
				before, identity.hashCode());
	}

	/**
	 * The identity is immutable as a cache key regardless of how it is built or read:
	 * the canonical constructor snapshots the details it is handed directly, and the
	 * {@code details()} accessor returns a fresh copy, so neither the caller's original
	 * details nor the accessor's result can mutate the stored key's value or hash.
	 */
	@Test(timeout = 10000)
	public void identitySnapshotsDetailsOnConstructionAndAccess() {
		PatternElement element = new PatternElement();
		ElementVoicingDetails details = new ElementVoicingDetails(ChannelInfo.Voicing.MAIN,
				ChannelInfo.StereoChannel.LEFT, false, null, 1.0, 2.0);

		// The canonical constructor (not just the of() factory) snapshots the details.
		RenderedNoteAudio.Identity identity = new RenderedNoteAudio.Identity(element, details,
				ChannelInfo.StereoChannel.LEFT);
		Assert.assertNotSame("the canonical constructor snapshots the details rather than aliasing them",
				details, identity.details());

		int before = identity.hashCode();
		details.setPosition(99.0);
		Assert.assertEquals("mutating the constructor argument does not change the key",
				1.0, identity.details().getPosition(), 0.0);
		Assert.assertEquals("the key keeps a stable hash after a mutation of the constructor argument",
				before, identity.hashCode());

		// The accessor returns a copy, so mutating it cannot corrupt the stored key.
		ElementVoicingDetails exposed = identity.details();
		exposed.setPosition(-5.0);
		Assert.assertNotSame("each access returns a distinct snapshot", exposed, identity.details());
		Assert.assertEquals("mutating the accessor result does not change the key",
				1.0, identity.details().getPosition(), 0.0);
		Assert.assertEquals("the key keeps a stable hash after a mutation of an accessor result",
				before, identity.hashCode());
	}

	/**
	 * A rendered note owns the single-element offset argument used to pass its start
	 * frame to producers; teardown destroys it and clears the reference, and a
	 * repeated teardown (or a note with no offset argument) is a harmless no-op.
	 */
	@Test(timeout = 10000)
	public void destroyReleasesOffsetArg() {
		RenderedNoteAudio note = new RenderedNoteAudio(0, 0);
		PackedCollection offsetArg = new PackedCollection(1);
		note.setOffsetArg(offsetArg);

		note.destroy();
		Assert.assertTrue("teardown destroys the owned offset argument", offsetArg.isDestroyed());
		Assert.assertNull("the offset argument reference is cleared", note.getOffsetArg());

		note.destroy();
		Assert.assertNull("a repeated teardown is a no-op", note.getOffsetArg());

		RenderedNoteAudio noArg = new RenderedNoteAudio(0, 0);
		noArg.destroy();
		Assert.assertNull("a note with no offset argument tears down without fault", noArg.getOffsetArg());
	}

	/**
	 * Because a rendered note owns its offset argument, replacing it with a different
	 * instance releases the previous allocation rather than stranding it, while setting
	 * the same instance again is a no-op that leaves it live.
	 */
	@Test(timeout = 10000)
	public void setOffsetArgReleasesReplacedAllocation() {
		RenderedNoteAudio note = new RenderedNoteAudio(0, 0);
		PackedCollection first = new PackedCollection(1);
		PackedCollection second = new PackedCollection(1);

		note.setOffsetArg(first);
		Assert.assertSame(first, note.getOffsetArg());

		note.setOffsetArg(first);
		Assert.assertFalse("setting the same instance does not release it", first.isDestroyed());
		Assert.assertSame(first, note.getOffsetArg());

		note.setOffsetArg(second);
		Assert.assertTrue("replacing a different instance releases the previous one", first.isDestroyed());
		Assert.assertFalse("the replacement stays live", second.isDestroyed());
		Assert.assertSame(second, note.getOffsetArg());

		note.destroy();
		Assert.assertTrue("teardown releases the current offset argument", second.isDestroyed());
	}

	/**
	 * Clearing the offset argument with {@code null} is a documented no-op: it must leave
	 * the note's current allocation live and still referenced, because the note alone owns
	 * that argument and only {@link RenderedNoteAudio#destroy()} may release it.
	 */
	@Test(timeout = 10000)
	public void setOffsetArgNullLeavesCurrentAllocationUntouched() {
		RenderedNoteAudio note = new RenderedNoteAudio(0, 0);
		PackedCollection current = new PackedCollection(1);

		note.setOffsetArg(current);
		note.setOffsetArg(null);
		Assert.assertFalse("passing null does not release the current argument", current.isDestroyed());
		Assert.assertSame("passing null does not clear the current reference", current, note.getOffsetArg());

		note.destroy();
		Assert.assertTrue("teardown still releases the retained offset argument", current.isDestroyed());
	}
}
