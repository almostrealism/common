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

package org.almostrealism.io;

import org.junit.Assert;
import org.junit.Test;

/**
 * Locks the {@link HostFingerprint#node()} contract directly in base/io: the
 * concise node identity is always a non-empty {@code "hostname (address)"}
 * string that never throws, and it is exactly the identity segment of the full
 * {@link HostFingerprint#describe()} block (everything before the {@code " | "}
 * separator), so the heartbeat payload's node and the diagnostic fingerprint
 * can never disagree on who the host is.
 */
public class HostFingerprintTest {

	/** The node identity is a non-empty single line shaped {@code hostname (address)}. */
	@Test(timeout = 10000)
	public void nodeIsNonEmptySingleLine() {
		String node = HostFingerprint.node();
		Assert.assertNotNull("node() must never return null", node);
		Assert.assertFalse("node() must never be empty", node.isEmpty());
		Assert.assertFalse("node() is a single line", node.contains("\n"));
		Assert.assertTrue("node() carries the address in parentheses",
				node.contains(" (") && node.endsWith(")"));
	}

	/** The node identity matches the identity segment of the full fingerprint. */
	@Test(timeout = 10000)
	public void nodeMatchesDescribeIdentitySegment() {
		String describe = HostFingerprint.describe();
		int sep = describe.indexOf(" | ");
		Assert.assertTrue("describe() starts with the identity segment", sep > 0);
		Assert.assertEquals("node() is exactly describe()'s identity segment",
				describe.substring(0, sep), HostFingerprint.node());
	}
}
