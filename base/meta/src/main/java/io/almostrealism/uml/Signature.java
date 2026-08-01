/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism.uml;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * An interface for objects that can produce a string signature representing their <em>structural</em>
 * identity — what the object represents or computes, not which instance it happens to be.
 *
 * <p>This interface provides a standardized way for objects to generate signatures that identify
 * their structure or configuration. Signatures are used for caching, deduplication, identity
 * checking, and optimization in computational graphs.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code Signature} is designed for:</p>
 * <ul>
 *   <li><strong>Caching:</strong> Identifying identical computations to reuse cached results</li>
 *   <li><strong>Deduplication:</strong> Detecting duplicate objects in computational graphs</li>
 *   <li><strong>Identity Checking:</strong> Determining if two objects are structurally equivalent</li>
 *   <li><strong>Optimization:</strong> Enabling signature-based optimizations in process graphs</li>
 * </ul>
 *
 * <h2>Signature Properties</h2>
 * <p>A good signature should be:</p>
 * <ul>
 *   <li><strong>Structural:</strong> Two objects produce the <em>same</em> signature whenever they
 *       are structurally/semantically equivalent — i.e. they represent the same computation or
 *       value shape — <em>even when they are distinct instances built over different input
 *       objects</em>. Conversely, structurally different objects produce different signatures.</li>
 *   <li><strong>Deterministic:</strong> The same structure always produces the same signature</li>
 *   <li><strong>Compact:</strong> Reasonably short for efficient comparison and storage</li>
 * </ul>
 *
 * <h2>What a signature is NOT</h2>
 * <p>A signature is <strong>not</strong> a per-instance identity token. Read every use of the word
 * "identity" in this interface as <em>structural</em> identity — what the object represents or
 * computes — and <strong>never</strong> as JVM object identity, a memory address, or
 * {@link System#identityHashCode(Object)}. Two separately-allocated objects that are structurally
 * equivalent <strong>must</strong> produce <em>equal</em> signatures. That is the entire purpose:
 * it is what lets the instruction cache reuse one compiled kernel across distinct computations of
 * the same shape.</p>
 *
 * <p>Concretely: {@code add(cp(a), cp(b))} and {@code add(cp(c), cp(d))} — where {@code a},
 * {@code b}, {@code c}, {@code d} are <em>different</em> constant collection inputs of the same
 * shape — produce the <em>same</em> signature; the constant <em>instances</em> do not enter it.
 * Only the structure does, so {@code subtract(cp(a), cp(b))} produces a <em>different</em>
 * signature. (Verified by {@code ConstantInputSignatureTest}.) Do <strong>not</strong> infer from
 * "represents their identity" that signatures are unique per instance — an implementation whose
 * signature varied from one instance to another structurally-equivalent instance would silently
 * defeat caching, and is a bug, not the contract.</p>
 *
 * <h2>Default Implementation</h2>
 * <p>The default {@link #signature()} implementation throws {@link UnsupportedOperationException},
 * allowing implementations to opt-in to signature generation. This is appropriate for objects
 * where signature generation is optional or context-dependent.</p>
 *
 * <h2>Utility Methods</h2>
 * <p>This interface provides static utility methods for signature handling:</p>
 * <ul>
 *   <li>{@link #of(Object)} - Safe signature extraction from any object</li>
 *   <li>{@link #md5(String)} - Generate MD5 hash of a signature string</li>
 *   <li>{@link #hex(byte[])} - Convert byte array to hexadecimal string</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Basic implementation:</strong></p>
 * <pre>{@code
 * public class Operation implements Signature {
 *     private final String type;
 *     private final List<String> parameters;
 *
 *     @Override
 *     public String signature() {
 *         return type + ":" + String.join(",", parameters);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Signature-based caching:</strong></p>
 * <pre>{@code
 * public class ComputationCache {
 *     private Map<String, Result> cache = new HashMap<>();
 *
 *     public Result compute(Signature computation) {
 *         String sig = computation.signature();
 *         if (cache.containsKey(sig)) {
 *             return cache.get(sig);  // Cache hit
 *         }
 *         Result result = executeComputation(computation);
 *         cache.put(sig, result);
 *         return result;
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Using MD5 for compact signatures:</strong></p>
 * <pre>{@code
 * public class ComplexOperation implements Signature {
 *     private String config;  // Potentially large configuration
 *
 *     @Override
 *     public String signature() {
 *         // Use MD5 to produce compact signature from large config
 *         return Signature.md5(config);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Safe signature extraction:</strong></p>
 * <pre>{@code
 * Object obj = getObject();
 * String sig = Signature.of(obj);
 * if (sig != null) {
 *     // Object has a signature
 *     processWithSignature(obj, sig);
 * } else {
 *     // Object doesn't support signatures
 *     processWithoutSignature(obj);
 * }
 * }</pre>
 *
 * <p><strong>Deduplication using signatures:</strong></p>
 * <pre>{@code
 * public List<Computation> removeDuplicates(List<Computation> computations) {
 *     Map<String, Computation> unique = new HashMap<>();
 *     for (Computation comp : computations) {
 *         String sig = comp.signature();
 *         if (!unique.containsKey(sig)) {
 *             unique.put(sig, comp);
 *         }
 *     }
 *     return new ArrayList<>(unique.values());
 * }
 * }</pre>
 *
 * @see Named
 */
public interface Signature {

	/**
	 * Returns a unique string signature representing this object's identity.
	 *
	 * <p>The signature should uniquely identify this object's state, configuration, or
	 * structure in a way that allows equivalent objects to be recognized. The exact
	 * format and content of the signature is implementation-specific.</p>
	 *
	 * <p>The default implementation throws {@link UnsupportedOperationException}, indicating
	 * that this object does not support signature generation. Implementations should override
	 * this method to provide meaningful signatures.</p>
	 *
	 * <p><strong>Implementation Guidelines:</strong></p>
	 * <ul>
	 *   <li>Ensure deterministic: same state -> same signature</li>
	 *   <li>Include all identity-relevant information</li>
	 *   <li>Exclude transient or irrelevant details</li>
	 *   <li>Consider using {@link #md5(String)} for large signatures</li>
	 *   <li>Document signature format and what it includes</li>
	 * </ul>
	 *
	 * @return A unique string signature for this object
	 * @throws UnsupportedOperationException if signature generation is not supported (default)
	 */
	default String signature() { throw new UnsupportedOperationException(); }

	/**
	 * Safely extracts a signature from any object, handling non-Signature types.
	 *
	 * <p>This utility method provides a safe way to get a signature from an object
	 * that may or may not implement {@link Signature}:</p>
	 * <ul>
	 *   <li>If {@code sig} implements {@link Signature}, returns {@code sig.signature()}</li>
	 *   <li>Otherwise, returns {@code null}</li>
	 * </ul>
	 *
	 * <p>This is useful when working with mixed-type collections or when signature
	 * availability is determined at runtime.</p>
	 *
	 * @param <T> The type of the object
	 * @param sig The object to extract a signature from
	 * @return The object's signature, or {@code null} if it doesn't implement Signature
	 */
	static <T> String of(T sig) {
		if (sig instanceof Signature) {
			return ((Signature) sig).signature();
		}

		return null;
	}

	/**
	 * Computes the MD5 hash of a signature string.
	 *
	 * <p>This method generates a 32-character hexadecimal MD5 hash of the input string.
	 * MD5 is useful for creating compact, fixed-length signatures from potentially large
	 * or variable-length signature strings.</p>
	 *
	 * <p><strong>Use Cases:</strong></p>
	 * <ul>
	 *   <li>Compacting large configuration signatures</li>
	 *   <li>Creating fixed-length cache keys</li>
	 *   <li>Generating consistent hash-based identifiers</li>
	 * </ul>
	 *
	 * <p><strong>Note:</strong> MD5 is not cryptographically secure and should not be used
	 * for security purposes. It is appropriate for non-adversarial identity checking and
	 * caching.</p>
	 *
	 * @param sig The signature string to hash
	 * @return A 32-character hexadecimal MD5 hash of the input
	 * @throws RuntimeException if MD5 algorithm is not available (should not occur in practice)
	 */
	static String md5(String sig) {
		try {
			return hex(MessageDigest.getInstance("MD5").digest(sig.getBytes()));
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Converts a byte array to a lowercase hexadecimal string.
	 *
	 * <p>This utility method converts each byte to its two-digit hexadecimal representation,
	 * with zero-padding as needed. It is commonly used to convert hash digests to readable
	 * string format.</p>
	 *
	 * <p><strong>Example:</strong></p>
	 * <pre>{@code
	 * byte[] bytes = {(byte) 0xAB, (byte) 0xCD, (byte) 0x01};
	 * String hex = Signature.hex(bytes);  // "abcd01"
	 * }</pre>
	 *
	 * @param bytes The byte array to convert
	 * @return A lowercase hexadecimal string representation of the bytes
	 */
	static String hex(byte[] bytes) {
		StringBuilder hexString = new StringBuilder();
		for (byte b : bytes) {
			String hex = Integer.toHexString(0xff & b);
			if (hex.length() == 1) hexString.append('0');
			hexString.append(hex);
		}
		return hexString.toString();
	}
}

