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

package org.almostrealism.studio.test;

import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests {@link MixdownManager#supportsPdslMixdown(List)}, the capability contract that
 * decides whether a channel selection may render through the PDSL mixdown path rather
 * than the Java fallback. Any single channel is supported; a multi-channel selection is
 * supported only when it is the zero-based contiguous prefix [0, 1, ..., n-1].
 */
public class MixdownManagerTest extends TestSuiteBase {

	/** An empty selection has nothing to render and is not supported. */
	@Test(timeout = 10000)
	public void emptySelectionIsUnsupported() {
		Assert.assertFalse(MixdownManager.supportsPdslMixdown(new ArrayList<>()));
	}

	/** A single channel is always supported, including a non-zero index. */
	@Test(timeout = 10000)
	public void anySingleChannelIsSupported() {
		Assert.assertTrue(MixdownManager.supportsPdslMixdown(List.of(0)));
		Assert.assertTrue(MixdownManager.supportsPdslMixdown(List.of(3)));
		Assert.assertTrue(MixdownManager.supportsPdslMixdown(List.of(17)));
	}

	/** The zero-based contiguous multi-channel prefix (the full-scene render) is supported. */
	@Test(timeout = 10000)
	public void contiguousZeroBasedPrefixIsSupported() {
		Assert.assertTrue(MixdownManager.supportsPdslMixdown(List.of(0, 1)));
		Assert.assertTrue(MixdownManager.supportsPdslMixdown(List.of(0, 1, 2, 3)));
	}

	/** A multi-channel selection that does not start at zero is unsupported. */
	@Test(timeout = 10000)
	public void nonZeroBasedMultiChannelIsUnsupported() {
		Assert.assertFalse(MixdownManager.supportsPdslMixdown(List.of(1, 2)));
	}

	/** A non-contiguous multi-channel selection is unsupported (the feedback grid is bank-indexed). */
	@Test(timeout = 10000)
	public void nonContiguousMultiChannelIsUnsupported() {
		Assert.assertFalse(MixdownManager.supportsPdslMixdown(List.of(0, 2)));
		Assert.assertFalse(MixdownManager.supportsPdslMixdown(List.of(0, 1, 3)));
	}

	/** A selection ordered out of sequence is not the contiguous prefix and is unsupported. */
	@Test(timeout = 10000)
	public void outOfOrderMultiChannelIsUnsupported() {
		Assert.assertFalse(MixdownManager.supportsPdslMixdown(List.of(1, 0)));
	}

	/** A selection containing a null channel index is unsupported rather than throwing. */
	@Test(timeout = 10000)
	public void nullChannelIsUnsupported() {
		Assert.assertFalse(MixdownManager.supportsPdslMixdown(Arrays.asList(0, null)));
	}
}
