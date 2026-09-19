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

package org.almostrealism.chem.test;

import org.almostrealism.chem.Element;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Verifies the integrity of the hand-maintained periodic-table data exposed by
 * {@link Element.Periods} and {@link Element.Groups}. The seven periods together
 * partition the full set of {@link Element} constants: every element belongs to
 * exactly one period, with none omitted and none duplicated. The halogen group
 * must match its documented membership.
 */
public class ElementPeriodicTableTest extends TestSuiteBase {

	/**
	 * Collects the seven period lists into a single list, preserving duplicates.
	 *
	 * @return every element listed across Periods 1 through 7
	 */
	private static List<Element> allPeriodElements() {
		List<Element> all = new ArrayList<>();
		all.addAll(Element.Periods.first());
		all.addAll(Element.Periods.second());
		all.addAll(Element.Periods.third());
		all.addAll(Element.Periods.fourth());
		all.addAll(Element.Periods.fifth());
		all.addAll(Element.Periods.sixth());
		all.addAll(Element.Periods.seventh());
		return all;
	}

	/**
	 * The seven periods must partition the periodic table: each of the 118
	 * elements appears in exactly one period, with no duplicates and none
	 * missing.
	 */
	@Test(timeout = 5000)
	public void everyElementBelongsToExactlyOnePeriod() {
		List<Element> all = allPeriodElements();
		Set<Element> distinct = new LinkedHashSet<>(all);

		Assert.assertEquals("An element appears in more than one period slot",
				all.size(), distinct.size());

		for (Element e : Element.values()) {
			Assert.assertTrue("Period tables omit " + e + " (Z=" + e.getAtomicNumber() + ")",
					distinct.contains(e));
		}

		Assert.assertEquals("Periods must cover all elements exactly once",
				Element.values().length, distinct.size());
	}

	/**
	 * Period 6 must contain Thulium (Z=69), which sits between Erbium (Z=68)
	 * and Ytterbium (Z=70).
	 */
	@Test(timeout = 5000)
	public void periodSixContainsThulium() {
		Assert.assertTrue("Period 6 must contain Thulium",
				Element.Periods.sixth().contains(Element.Thulium));
	}

	/**
	 * Period 7 must contain Plutonium (Z=94), which follows Neptunium (Z=93).
	 */
	@Test(timeout = 5000)
	public void periodSevenContainsPlutonium() {
		Assert.assertTrue("Period 7 must contain Plutonium",
				Element.Periods.seventh().contains(Element.Plutonium));
	}

	/**
	 * Group 17 (the halogens) must include Bromine (Z=35), as documented.
	 */
	@Test(timeout = 5000)
	public void halogensIncludeBromine() {
		Assert.assertTrue("Group 17 (halogens) must include Bromine",
				Element.halogens().contains(Element.Bromine));
	}
}
