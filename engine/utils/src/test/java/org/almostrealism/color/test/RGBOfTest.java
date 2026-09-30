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

package org.almostrealism.color.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link RGB#of(PackedCollection)}, the shared factory that normalises an
 * evaluated color result to an {@link RGB}. These tests pin the behavior previously
 * open-coded at every color-result call site: an {@link RGB} is returned unchanged,
 * and any other {@link PackedCollection} is wrapped by reading channels 0, 1 and 2.
 */
public class RGBOfTest extends TestSuiteBase {

	/** Verifies that an argument which already is an {@link RGB} is returned as the same instance. */
	@Test(timeout = 5000)
	public void alreadyRGBReturnsSameInstance() {
		RGB color = new RGB(0.25, 0.5, 0.75);
		Assert.assertSame(color, RGB.of(color));
	}

	/** Verifies that a plain {@link PackedCollection} is wrapped by reading channels 0, 1 and 2. */
	@Test(timeout = 5000)
	public void plainCollectionIsWrappedFromChannels() {
		PackedCollection pc = PackedCollection.of(0.25, 0.5, 0.75);
		RGB color = RGB.of(pc);

		Assert.assertNotSame(pc, color);
		Assert.assertEquals(0.25, color.getRed(), 1e-6);
		Assert.assertEquals(0.5, color.getGreen(), 1e-6);
		Assert.assertEquals(0.75, color.getBlue(), 1e-6);
	}

	/**
	 * Verifies that channel values are stored without clamping, matching the
	 * {@link RGB#RGB(double, double, double)} constructor used by the original open-coded idiom.
	 * A value above 1.0 and a negative value must survive intact rather than being clamped
	 * to the [0, 1] range enforced by the setters.
	 */
	@Test(timeout = 5000)
	public void wrappedChannelsAreNotClamped() {
		PackedCollection pc = PackedCollection.of(2.0, -0.5, 0.0);
		RGB color = RGB.of(pc);

		Assert.assertEquals(2.0, color.getRed(), 1e-6);
		Assert.assertEquals(-0.5, color.getGreen(), 1e-6);
		Assert.assertEquals(0.0, color.getBlue(), 1e-6);
	}
}
