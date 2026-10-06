/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.studio;

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.studio.arrange.AutomationManager;
import org.almostrealism.studio.arrange.EfxManager;
import org.almostrealism.studio.arrange.GlobalTimeManager;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the consolidated-buffer release contract shared by
 * {@link PatternRenderBuffers} and {@link EfxManager}.
 *
 * <p>Both own a single contiguous {@link PackedCollection} that backs a runner build's
 * render-cell (or filter-destination) regions. A scene reconsolidates on every build, so
 * re-consolidation must free the previous root rather than orphan it — otherwise a repeated
 * {@code renderChannel}, or a failed-then-retried runner build on a long-lived scene, leaks
 * the prior buffer until the whole scene is destroyed.</p>
 *
 * @see PatternRenderBuffers#consolidate(int, int)
 * @see EfxManager#consolidateFilterBuffers(int, int)
 */
public class BufferConsolidationReleaseTest extends TestSuiteBase {

	/** Sample rate for fixtures that require one. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/**
	 * Verifies that a second {@link PatternRenderBuffers#consolidate(int, int)} frees the
	 * buffer the first one allocated, and that {@link PatternRenderBuffers#destroy()} frees
	 * the surviving one.
	 */
	@Test(timeout = 60_000)
	public void renderBufferReconsolidationFreesPreviousRoot() {
		PatternRenderBuffers buffers = new PatternRenderBuffers();

		buffers.consolidate(2, 16);
		PackedCollection first = buffers.getBuffer();
		assertNotNull("first consolidation must allocate a buffer", first);
		assertFalse("freshly allocated buffer must not be destroyed", first.isDestroyed());

		buffers.consolidate(2, 16);
		PackedCollection second = buffers.getBuffer();
		assertNotNull("second consolidation must allocate a buffer", second);
		assertNotSame("re-consolidation must allocate a new root", first, second);
		assertTrue("re-consolidation must free the previous root", first.isDestroyed());
		assertFalse("replacement root must remain live", second.isDestroyed());

		buffers.destroy();
		assertTrue("destroy must free the surviving root", second.isDestroyed());
	}

	/**
	 * Verifies that a second {@link EfxManager#consolidateFilterBuffers(int, int)} frees the
	 * filter buffer the first one allocated.
	 */
	@Test(timeout = 60_000)
	public void filterBufferReconsolidationFreesPreviousRoot() {
		ProjectedGenome genome = new ProjectedGenome(256);
		GlobalTimeManager time = new GlobalTimeManager(measure -> measure * SAMPLE_RATE);
		AutomationManager automation = new AutomationManager(
				genome.addChromosome(), time.getClock(), () -> 1.0, SAMPLE_RATE);
		EfxManager efx = new EfxManager(
				genome.addChromosome(), 2, automation, () -> 0.25, SAMPLE_RATE);

		try {
			efx.consolidateFilterBuffers(2, 16);
			PackedCollection first = efx.getConsolidatedFilterBuffer();
			assertNotNull("first consolidation must allocate a filter buffer", first);
			assertFalse("freshly allocated filter buffer must not be destroyed", first.isDestroyed());

			efx.consolidateFilterBuffers(2, 16);
			PackedCollection second = efx.getConsolidatedFilterBuffer();
			assertNotNull("second consolidation must allocate a filter buffer", second);
			assertNotSame("re-consolidation must allocate a new filter root", first, second);
			assertTrue("re-consolidation must free the previous filter root", first.isDestroyed());
			assertFalse("replacement filter root must remain live", second.isDestroyed());
		} finally {
			efx.destroyConsolidatedBuffers();
		}
	}
}
