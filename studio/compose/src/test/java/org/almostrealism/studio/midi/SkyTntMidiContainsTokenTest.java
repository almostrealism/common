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

package org.almostrealism.studio.midi;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests {@link SkyTntMidi#containsToken(int[], int)} directly, in the same package as
 * the package-private method under test. This predicate guards the masked-sample safety
 * net in {@link SkyTntMidi#generate}: a sampled token outside the mask stops generation
 * as if EOS were reached rather than being fed into the next step's tokenizer lookup.
 */
public class SkyTntMidiContainsTokenTest extends TestSuiteBase {

	/** A token present in the array is found. */
	@Test(timeout = 10000)
	public void findsPresentToken() {
		Assert.assertTrue(SkyTntMidi.containsToken(new int[] { 3, 7, 11 }, 7));
	}

	/** A token absent from the array is not found. */
	@Test(timeout = 10000)
	public void rejectsAbsentToken() {
		Assert.assertFalse(SkyTntMidi.containsToken(new int[] { 3, 7, 11 }, 8));
	}

	/** An empty array never contains any token, including zero. */
	@Test(timeout = 10000)
	public void rejectsEverythingForEmptyArray() {
		Assert.assertFalse(SkyTntMidi.containsToken(new int[0], 0));
	}

	/** Both boundary positions of the array are checked correctly. */
	@Test(timeout = 10000)
	public void matchesAtFirstAndLastPosition() {
		int[] validIds = { 5, 9, 13, 21 };
		Assert.assertTrue(SkyTntMidi.containsToken(validIds, validIds[0]));
		Assert.assertTrue(SkyTntMidi.containsToken(validIds, validIds[validIds.length - 1]));
	}

	/** Negative token values are compared correctly, not just non-negative ones. */
	@Test(timeout = 10000)
	public void distinguishesNegativeTokens() {
		Assert.assertTrue(SkyTntMidi.containsToken(new int[] { -1, 0, 1 }, -1));
		Assert.assertFalse(SkyTntMidi.containsToken(new int[] { -1, 0, 1 }, -2));
	}
}
