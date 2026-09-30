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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the ownership contract of {@link NoteAudioCache}: the cache owns the
 * audio it holds and releases it whenever an entry is displaced, evicted or cleared.
 * Eviction measures a note by the count of its audio's shape, so test audio is
 * traversed per frame exactly as rendered note audio is.
 */
public class NoteAudioCacheTest extends TestSuiteBase {

	/**
	 * Creates note audio of the given length, traversed per frame as the render
	 * path produces it, so that its count is its frame length.
	 *
	 * @param frames the number of frames
	 * @return the audio
	 */
	private PackedCollection audio(int frames) {
		return new PackedCollection(shape(frames).traverseEach());
	}

	/** Entries are keyed by note start frame. */
	@Test(timeout = 10000)
	public void entriesAreKeyedByOffset() {
		NoteAudioCache cache = new NoteAudioCache();
		PackedCollection a = audio(16);
		PackedCollection b = audio(16);

		cache.put(0, a);
		cache.put(100, b);

		Assert.assertEquals(2, cache.size());
		Assert.assertSame(a, cache.get(0));
		Assert.assertSame(b, cache.get(100));
		Assert.assertNull(cache.get(50));
	}

	/** Replacing an entry releases the displaced audio but not a re-inserted identical entry. */
	@Test(timeout = 10000)
	public void displacedAudioIsReleased() {
		NoteAudioCache cache = new NoteAudioCache();
		PackedCollection first = audio(16);
		PackedCollection second = audio(16);

		cache.put(10, first);
		cache.put(10, first);
		Assert.assertFalse("re-inserting the same audio must not release it", first.isDestroyed());

		cache.put(10, second);
		Assert.assertTrue(first.isDestroyed());
		Assert.assertFalse(second.isDestroyed());
		Assert.assertSame(second, cache.get(10));
		Assert.assertEquals(1, cache.size());
	}

	/**
	 * Eviction removes and releases exactly the notes that end at or before the
	 * current start frame; a note that ends at the frame is evicted, one that
	 * ends after it is kept.
	 */
	@Test(timeout = 10000)
	public void evictionUsesNoteEnd() {
		NoteAudioCache cache = new NoteAudioCache();
		PackedCollection early = audio(10);
		PackedCollection endsAt101 = audio(61);
		PackedCollection later = audio(10);

		cache.put(0, early);
		cache.put(40, endsAt101);
		cache.put(1040, later);

		cache.evictBefore(100);
		Assert.assertEquals(2, cache.size());
		Assert.assertNull(cache.get(0));
		Assert.assertTrue(early.isDestroyed());
		Assert.assertSame("a note still sounding at the start frame is kept", endsAt101, cache.get(40));
		Assert.assertFalse(endsAt101.isDestroyed());

		cache.evictBefore(101);
		Assert.assertEquals(1, cache.size());
		Assert.assertTrue("a note ending exactly at the start frame is evicted", endsAt101.isDestroyed());
		Assert.assertSame(later, cache.get(1040));
		Assert.assertFalse(later.isDestroyed());
	}

	/** Clearing releases every held collection and empties the cache. */
	@Test(timeout = 10000)
	public void clearReleasesEverything() {
		NoteAudioCache cache = new NoteAudioCache();
		PackedCollection a = audio(8);
		PackedCollection b = audio(8);
		cache.put(1, a);
		cache.put(2, b);

		cache.clear();

		Assert.assertEquals(0, cache.size());
		Assert.assertTrue(a.isDestroyed());
		Assert.assertTrue(b.isDestroyed());
		Assert.assertNull(cache.get(1));
	}
}
