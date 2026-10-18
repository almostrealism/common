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

package org.almostrealism.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Pins the identifier and hashing contract of {@link KeyUtils}.
 *
 * <p>These tests fix the observable properties callers rely on: generated
 * keys are unique UUIDs, and {@link KeyUtils#hash(String)} produces the
 * canonical lowercase 64-character SHA-256 digest with known test vectors.</p>
 */
public class KeyUtilsTest extends TestSuiteBase {

	/** A generated key is a well-formed 36-character UUID string. */
	@Test(timeout = 10000)
	public void generateKeyProducesUuidFormat() {
		String key = KeyUtils.generateKey();
		Assert.assertEquals(36, key.length());
		// UUID canonical form: 8-4-4-4-12 hex digits separated by hyphens.
		Assert.assertTrue("Not a UUID: " + key,
				key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
	}

	/** Successive calls return distinct identifiers. */
	@Test(timeout = 10000)
	public void generateKeyProducesDistinctKeys() {
		Set<String> keys = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			keys.add(KeyUtils.generateKey());
		}
		Assert.assertEquals("Generated keys collided", 1000, keys.size());
	}

	/** SHA-256 of the empty string matches the published digest. */
	@Test(timeout = 10000)
	public void hashOfEmptyStringMatchesKnownVector() {
		Assert.assertEquals(
				"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
				KeyUtils.hash(""));
	}

	/** SHA-256 of "abc" matches the published digest. */
	@Test(timeout = 10000)
	public void hashOfAbcMatchesKnownVector() {
		Assert.assertEquals(
				"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
				KeyUtils.hash("abc"));
	}

	/** The hash is a stable, lowercase, 64-character hex string. */
	@Test(timeout = 10000)
	public void hashIsDeterministicLowercaseHex() {
		String first = KeyUtils.hash("almostrealism");
		String second = KeyUtils.hash("almostrealism");
		Assert.assertEquals("Hash is not deterministic", first, second);
		Assert.assertEquals(64, first.length());
		Assert.assertTrue("Hash is not lowercase hex", first.matches("[0-9a-f]{64}"));
	}

	/** Distinct inputs hash to distinct digests. */
	@Test(timeout = 10000)
	public void hashDistinguishesInputs() {
		Assert.assertNotEquals(KeyUtils.hash("password1"), KeyUtils.hash("password2"));
	}

	/** {@link KeyUtils#bytesToHex(byte[])} renders each byte as two lowercase hex digits. */
	@Test(timeout = 10000)
	public void bytesToHexRendersEveryByte() {
		byte[] bytes = {0x00, 0x0f, (byte) 0xa5, (byte) 0xff, 0x10};
		Assert.assertEquals("000fa5ff10", KeyUtils.bytesToHex(bytes));
	}

	/** An empty byte array renders as an empty string. */
	@Test(timeout = 10000)
	public void bytesToHexOfEmptyArrayIsEmpty() {
		Assert.assertEquals("", KeyUtils.bytesToHex(new byte[0]));
	}
}
