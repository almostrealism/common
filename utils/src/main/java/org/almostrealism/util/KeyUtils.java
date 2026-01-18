/*
 * Copyright 2024 Michael Murray
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Utilities for generating unique identifiers and cryptographic hashes.
 *
 * <p>This class provides static methods for:</p>
 * <ul>
 *   <li>Generating unique identifiers (UUIDs)</li>
 *   <li>Computing SHA-256 hashes of strings</li>
 *   <li>Converting byte arrays to hexadecimal strings</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Generate a unique key
 * String key = KeyUtils.generateKey();
 * // Example: "550e8400-e29b-41d4-a716-446655440000"
 *
 * // Hash a password or data
 * String hash = KeyUtils.hash("myPassword");
 * // Returns 64-character hex string (SHA-256)
 * }</pre>
 *
 * @author Michael Murray
 */
public class KeyUtils {

	/**
	 * Generates a new random UUID as a string.
	 *
	 * @return a unique identifier string in standard UUID format
	 *         (e.g., "550e8400-e29b-41d4-a716-446655440000")
	 */
	public static String generateKey() {
		return UUID.randomUUID().toString();
	}

	/**
	 * Computes the SHA-256 hash of a string value.
	 *
	 * @param value the string to hash (encoded as UTF-8)
	 * @return the hash as a lowercase hexadecimal string (64 characters)
	 * @throws RuntimeException if SHA-256 algorithm is not available (should not occur)
	 */
	public static String hash(String value) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
			return bytesToHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Converts a byte array to a lowercase hexadecimal string.
	 *
	 * @param bytes the byte array to convert
	 * @return the hexadecimal representation (2 hex digits per byte)
	 */
	public static String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}
}
