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

package io.flowtree.workstream;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link AlertRecipientEntry#numbers(List)} and
 * {@link AlertRecipientEntry#consentSummary()}, the opt-out lookup map and the
 * consent record summary the SMS compliance path relies on.
 */
public class AlertRecipientEntryTest extends TestSuiteBase {

	/**
	 * Builds an entry with the given handle and number.
	 *
	 * @param name   the recipient handle
	 * @param number the destination number
	 * @return the entry
	 */
	private AlertRecipientEntry entry(String name, String number) {
		AlertRecipientEntry entry = new AlertRecipientEntry();
		entry.setName(name);
		entry.setSmsNumber(number);
		return entry;
	}

	/** A null entry list yields an empty, non-null map. */
	@Test(timeout = 10000)
	public void testNullEntriesYieldsEmptyMap() {
		Map<String, String> numbers = AlertRecipientEntry.numbers(null);
		assertTrue(numbers.isEmpty());
	}

	/** An empty entry list yields an empty map. */
	@Test(timeout = 10000)
	public void testEmptyEntriesYieldsEmptyMap() {
		assertTrue(AlertRecipientEntry.numbers(new ArrayList<>()).isEmpty());
	}

	/** Each named entry with a number contributes a handle to number mapping. */
	@Test(timeout = 10000)
	public void testNameToNumberMapping() {
		Map<String, String> numbers = AlertRecipientEntry.numbers(Arrays.asList(
				entry("michael", "+18335551234"), entry("mmurray", "+18335559999")));

		assertEquals(2, numbers.size());
		assertEquals("+18335551234", numbers.get("michael"));
		assertEquals("+18335559999", numbers.get("mmurray"));
	}

	/** A null element in the list is skipped rather than thrown on. */
	@Test(timeout = 10000)
	public void testNullEntryIsSkipped() {
		Map<String, String> numbers = AlertRecipientEntry.numbers(
				Arrays.asList(null, entry("michael", "+18335551234"), null));

		assertEquals(1, numbers.size());
		assertEquals("+18335551234", numbers.get("michael"));
	}

	/** An entry naming no number is omitted; opt-out cannot be keyed on it. */
	@Test(timeout = 10000)
	public void testEntryWithoutNumberIsOmitted() {
		assertTrue(AlertRecipientEntry.numbers(
				Collections.singletonList(entry("michael", null))).isEmpty());
	}

	/** An entry with a number but no handle is omitted. */
	@Test(timeout = 10000)
	public void testEntryWithoutNameIsOmitted() {
		assertTrue(AlertRecipientEntry.numbers(
				Collections.singletonList(entry(null, "+18335551234"))).isEmpty());
	}

	/** A blank handle or number is treated as absent and omitted. */
	@Test(timeout = 10000)
	public void testBlankNameOrNumberIsOmitted() {
		Map<String, String> numbers = AlertRecipientEntry.numbers(Arrays.asList(
				entry("   ", "+18335551234"), entry("michael", "  ")));

		assertTrue(numbers.isEmpty());
	}

	/** Surrounding whitespace is trimmed from both the handle and the number. */
	@Test(timeout = 10000)
	public void testNameAndNumberAreTrimmed() {
		Map<String, String> numbers = AlertRecipientEntry.numbers(
				Collections.singletonList(entry("  michael  ", "  +18335551234  ")));

		assertEquals(1, numbers.size());
		assertEquals("+18335551234", numbers.get("michael"));
	}

	/** Entries keep their configured order so the map reads predictably. */
	@Test(timeout = 10000)
	public void testInsertionOrderIsPreserved() {
		Map<String, String> numbers = AlertRecipientEntry.numbers(Arrays.asList(
				entry("zeta", "+18335550001"), entry("alpha", "+18335550002")));

		assertEquals(Arrays.asList("zeta", "alpha"),
				new ArrayList<>(numbers.keySet()));
	}

	/** No recorded consent method yields a null summary. */
	@Test(timeout = 10000)
	public void testConsentSummaryNullWhenNoMethod() {
		assertNull(new AlertRecipientEntry().consentSummary());
	}

	/** A blank consent method is treated as unrecorded. */
	@Test(timeout = 10000)
	public void testConsentSummaryNullWhenMethodBlank() {
		AlertRecipientEntry entry = new AlertRecipientEntry();
		entry.setConsentMethod("   ");
		assertNull(entry.consentSummary());
	}

	/** The method alone is the whole summary when nothing else is recorded. */
	@Test(timeout = 10000)
	public void testConsentSummaryMethodOnly() {
		AlertRecipientEntry entry = new AlertRecipientEntry();
		entry.setConsentMethod("verbal");
		assertEquals("verbal", entry.consentSummary());
	}

	/** The date is appended when present. */
	@Test(timeout = 10000)
	public void testConsentSummaryMethodAndDate() {
		AlertRecipientEntry entry = new AlertRecipientEntry();
		entry.setConsentMethod("verbal");
		entry.setConsentDate("2026-10-31");
		assertEquals("verbal on 2026-10-31", entry.consentSummary());
	}

	/** Method, date, and script version render as the full one-line record. */
	@Test(timeout = 10000)
	public void testConsentSummaryFull() {
		AlertRecipientEntry entry = new AlertRecipientEntry();
		entry.setConsentMethod("verbal");
		entry.setConsentDate("2026-10-31");
		entry.setConsentScriptVersion("v1");
		assertEquals("verbal on 2026-10-31 (script v1)", entry.consentSummary());
	}

	/** A missing date is skipped while a present script version still shows. */
	@Test(timeout = 10000)
	public void testConsentSummaryScriptWithoutDate() {
		AlertRecipientEntry entry = new AlertRecipientEntry();
		entry.setConsentMethod("verbal");
		entry.setConsentScriptVersion("v1");
		assertEquals("verbal (script v1)", entry.consentSummary());
	}

	/** Blank date and version fields are ignored rather than rendered. */
	@Test(timeout = 10000)
	public void testConsentSummaryIgnoresBlankFields() {
		AlertRecipientEntry entry = new AlertRecipientEntry();
		entry.setConsentMethod("verbal");
		entry.setConsentDate("  ");
		entry.setConsentScriptVersion("  ");
		assertEquals("verbal", entry.consentSummary());
	}

	/** Surrounding whitespace is trimmed from each rendered component. */
	@Test(timeout = 10000)
	public void testConsentSummaryTrimsComponents() {
		AlertRecipientEntry entry = new AlertRecipientEntry();
		entry.setConsentMethod("  verbal  ");
		entry.setConsentDate("  2026-10-31  ");
		entry.setConsentScriptVersion("  v1  ");
		assertEquals("verbal on 2026-10-31 (script v1)", entry.consentSummary());
	}
}
