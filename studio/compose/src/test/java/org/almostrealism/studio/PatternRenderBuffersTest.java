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

package org.almostrealism.studio;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the consolidated-buffer lifecycle of {@link PatternRenderBuffers}.
 *
 * <p>A single {@link PatternRenderBuffers} instance is reused across every runner
 * build on one {@code AudioScene}, so {@link PatternRenderBuffers#consolidate(int, int)}
 * must release the buffer retained from a previous build before allocating the
 * replacement — otherwise each rebuild would strand the prior consolidated
 * allocation (which {@code AudioScene.destroy()} can no longer reach once the field
 * has been overwritten) until reference-queue cleanup.</p>
 */
public class PatternRenderBuffersTest extends TestSuiteBase {

	/** Channels for the consolidated layout under test. */
	private static final int CHANNELS = 2;

	/** Frames per render region. */
	private static final int BUFFER_SIZE = 128;

	/**
	 * Confirms that re-consolidating destroys the previously allocated buffer and
	 * installs a correctly sized replacement, so repeated runner builds do not leak.
	 */
	@Test(timeout = 60000)
	public void reconsolidateReleasesPreviousBuffer() {
		PatternRenderBuffers buffers = new PatternRenderBuffers();

		buffers.consolidate(CHANNELS, BUFFER_SIZE);
		PackedCollection first = buffers.getBuffer();

		Assert.assertNotNull("First consolidation must allocate a buffer", first);
		Assert.assertNotNull("Freshly allocated buffer must hold memory", first.getMem());
		Assert.assertEquals("Buffer size must be bufferSize x channels x 4",
				BUFFER_SIZE * CHANNELS * 4, first.getMemLength());

		buffers.consolidate(CHANNELS, BUFFER_SIZE);
		PackedCollection second = buffers.getBuffer();

		Assert.assertNotSame("Re-consolidation must install a new buffer", first, second);
		Assert.assertNull("The previous buffer must be destroyed on re-consolidation",
				first.getMem());
		Assert.assertNotNull("The replacement buffer must hold memory", second.getMem());

		buffers.destroy();
		Assert.assertNull("destroy() must release the live buffer", second.getMem());
	}

	/**
	 * Confirms the first consolidation is safe when no buffer was previously allocated,
	 * so the added release guard does not fault on the initial build.
	 */
	@Test(timeout = 60000)
	public void firstConsolidateWithoutPriorBufferSucceeds() {
		PatternRenderBuffers buffers = new PatternRenderBuffers();

		Assert.assertNull("No buffer exists before the first consolidation",
				buffers.getBuffer());

		buffers.consolidate(CHANNELS, BUFFER_SIZE);

		Assert.assertNotNull("First consolidation must allocate a buffer",
				buffers.getBuffer());
		Assert.assertNotNull("Allocated buffer must hold memory",
				buffers.getBuffer().getMem());

		buffers.destroy();
	}
}
