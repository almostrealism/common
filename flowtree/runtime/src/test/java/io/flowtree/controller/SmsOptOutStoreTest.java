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

package io.flowtree.controller;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link SmsOptOutStore}, the persisted record of which numbers have
 * opted out of SMS.
 */
public class SmsOptOutStoreTest extends TestSuiteBase {

	/** The number used across the tests, in canonical E.164 form. */
	private static final String NUMBER = "+18335551234";

	/**
	 * Returns an opened store backed by a fresh temporary directory.
	 *
	 * @return the store under test
	 * @throws Exception if the temporary directory cannot be created
	 */
	protected SmsOptOutStore openStore() throws Exception {
		File dir = Files.createTempDirectory("sms-optout-test").toFile();
		dir.deleteOnExit();
		SmsOptOutStore store = new SmsOptOutStore(new File(dir, "sms-optout").getPath());
		assertTrue(store.initialize());
		return store;
	}

	/** A number is not opted out until it opts out, then is, then is not again. */
	@Test(timeout = 20000)
	public void testOptOutAndOptInRoundTrip() throws Exception {
		SmsOptOutStore store = openStore();
		try {
			assertFalse(store.isOptedOut(NUMBER));

			store.optOut(NUMBER);
			assertTrue(store.isOptedOut(NUMBER));

			store.optIn(NUMBER);
			assertFalse(store.isOptedOut(NUMBER));
		} finally {
			store.close();
		}
	}

	/** Opting the same number out twice is idempotent and stays opted out. */
	@Test(timeout = 20000)
	public void testOptOutIsIdempotent() throws Exception {
		SmsOptOutStore store = openStore();
		try {
			store.optOut(NUMBER);
			store.optOut(NUMBER);
			assertTrue(store.isOptedOut(NUMBER));
		} finally {
			store.close();
		}
	}

	/** Grouping punctuation and spaces do not defeat the match. */
	@Test(timeout = 20000)
	public void testCanonicalizationMatchesFormattedNumbers() throws Exception {
		SmsOptOutStore store = openStore();
		try {
			store.optOut("+1 (833) 555-1234");
			assertTrue(store.isOptedOut("+18335551234"));
		} finally {
			store.close();
		}
	}

	/** The opt-out survives closing and reopening the database. */
	@Test(timeout = 20000)
	public void testOptOutSurvivesReopen() throws Exception {
		File dir = Files.createTempDirectory("sms-optout-persist").toFile();
		dir.deleteOnExit();
		String path = new File(dir, "sms-optout").getPath();

		SmsOptOutStore first = new SmsOptOutStore(path);
		assertTrue(first.initialize());
		first.optOut(NUMBER);
		first.close();

		SmsOptOutStore second = new SmsOptOutStore(path);
		assertTrue(second.initialize());
		try {
			assertTrue(second.isOptedOut(NUMBER));
		} finally {
			second.close();
		}
	}
}
