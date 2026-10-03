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

		Object identity = new Object();
		note.setCacheIdentity(identity);
		Assert.assertSame(identity, note.getCacheIdentity());
	}
}
