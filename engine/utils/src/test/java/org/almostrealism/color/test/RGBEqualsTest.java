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

import org.almostrealism.color.RGB;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for epsilon-based {@link RGB#equals(Object)} and consistent
 * {@link RGB#hashCode()} behavior.
 */
public class RGBEqualsTest extends TestSuiteBase {

	/** Verifies that two RGB colors with identical channel values are equal and share the same hash code. */
	@Test(timeout = 5000)
	public void identicalColorsAreEqual() {
		RGB a = new RGB(0.5, 0.6, 0.7);
		RGB b = new RGB(0.5, 0.6, 0.7);
		Assert.assertTrue(a.equals(b));
		Assert.assertEquals(a.hashCode(), b.hashCode());
	}

	/** Verifies that colors within epsilon tolerance are considered equal. */
	@Test(timeout = 5000)
	public void nearlyIdenticalColorsAreEqual() {
		RGB a = new RGB(0.5, 0.6, 0.7);
		RGB b = new RGB(0.5 + 1e-5, 0.6 - 1e-5, 0.7 + 1e-6);
		Assert.assertTrue("Nearly identical colors should be equal", a.equals(b));
	}

	/** Verifies that colors with a channel difference exceeding epsilon are not equal. */
	@Test(timeout = 5000)
	public void distinctColorsAreNotEqual() {
		RGB a = new RGB(0.5, 0.6, 0.7);
		RGB b = new RGB(0.5, 0.6, 0.8);
		Assert.assertFalse("Distinct colors should not be equal", a.equals(b));
	}

	/** Verifies that the equals relation is symmetric: {@code a.equals(b) == b.equals(a)}. */
	@Test(timeout = 5000)
	public void symmetry() {
		RGB a = new RGB(0.1, 0.2, 0.3);
		RGB b = new RGB(0.1 + 1e-5, 0.2, 0.3);
		Assert.assertEquals(a.equals(b), b.equals(a));
	}

	/** Verifies that {@code equals(null)} returns false. */
	@Test(timeout = 5000)
	public void equalsWithNull() {
		RGB a = new RGB(0.5, 0.5, 0.5);
		Assert.assertFalse(a.equals(null));
	}

	/** Verifies that equals returns false for non-RGB objects. */
	@Test(timeout = 5000)
	public void equalsWithNonRGB() {
		RGB a = new RGB(0.5, 0.5, 0.5);
		Assert.assertFalse(a.equals("not an RGB"));
	}

	/** Verifies that identical colors produce the same hash code. */
	@Test(timeout = 5000)
	public void hashCodeConsistentForEqualColors() {
		RGB a = new RGB(0.333, 0.444, 0.555);
		RGB b = new RGB(0.333, 0.444, 0.555);
		Assert.assertEquals(a.hashCode(), b.hashCode());
	}
}
