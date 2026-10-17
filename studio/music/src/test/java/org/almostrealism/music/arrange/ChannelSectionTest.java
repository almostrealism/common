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

package org.almostrealism.music.arrange;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Supplier;

/**
 * Tests the {@link ChannelSection#contains(double)} default method, which
 * {@code AudioSceneContext.getSection} relies on to select the section covering a
 * measure position. The interval is half-open: inclusive at {@link ChannelSection#getPosition()}
 * and exclusive at {@code getPosition() + getLength()}.
 */
public class ChannelSectionTest extends TestSuiteBase {

	/**
	 * A minimal {@link ChannelSection} fixed at a position and length. Its
	 * {@link #process} is never invoked by these containment tests.
	 */
	private static final class FixedSection implements ChannelSection {
		/** The section start, in measures. */
		private final int position;

		/** The section length, in measures. */
		private final int length;

		/**
		 * Creates a section fixed at the given start and length.
		 *
		 * @param position the section start, in measures
		 * @param length the section length, in measures
		 */
		private FixedSection(int position, int length) {
			this.position = position;
			this.length = length;
		}

		@Override
		public int getPosition() { return position; }

		@Override
		public int getLength() { return length; }

		@Override
		public Supplier<Runnable> process(Producer<PackedCollection> destination,
										  Producer<PackedCollection> source) {
			throw new UnsupportedOperationException("not exercised by containment tests");
		}
	}

	/** The start measure is inside the section; the end measure is outside (half-open interval). */
	@Test(timeout = 10000)
	public void containsIsHalfOpen() {
		ChannelSection section = new FixedSection(4, 2);

		Assert.assertTrue("start is inclusive", section.contains(4.0));
		Assert.assertTrue("interior is covered", section.contains(5.0));
		Assert.assertTrue("just before the end is covered", section.contains(5.999));
		Assert.assertFalse("end is exclusive", section.contains(6.0));
	}

	/** Positions before the section start are not covered. */
	@Test(timeout = 10000)
	public void containsRejectsPositionsBeforeStart() {
		ChannelSection section = new FixedSection(4, 2);

		Assert.assertFalse(section.contains(3.999));
		Assert.assertFalse(section.contains(0.0));
	}

	/** A zero-length section contains no measure position, not even its own start. */
	@Test(timeout = 10000)
	public void zeroLengthSectionContainsNothing() {
		ChannelSection section = new FixedSection(4, 0);

		Assert.assertFalse(section.contains(4.0));
		Assert.assertFalse(section.contains(3.0));
		Assert.assertFalse(section.contains(5.0));
	}

	/** A section anchored at measure zero covers its fractional interior. */
	@Test(timeout = 10000)
	public void containsSectionAtOrigin() {
		ChannelSection section = new FixedSection(0, 1);

		Assert.assertTrue(section.contains(0.0));
		Assert.assertTrue(section.contains(0.5));
		Assert.assertFalse(section.contains(1.0));
	}
}
