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

package io.flowtree.fs;

import io.almostrealism.persist.ResourceHeaderParser;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link ConcatenatedResource.ConcatenatedResourceHeaderParser},
 * the {@link ResourceHeaderParser} that selects the {@link ConcatenatedResource}
 * type based on the resource's leading magic bytes.
 */
public class ConcatenatedResourceTest extends TestSuiteBase {

	/**
	 * The parser must recognise a header that begins with the
	 * {@code <ConcatenatedResource>} magic prefix regardless of any trailing
	 * directory content.
	 */
	@Test(timeout = 5000)
	public void headerParserMatchesMagicPrefix() {
		ResourceHeaderParser parser = new ConcatenatedResource.ConcatenatedResourceHeaderParser();
		Assert.assertTrue(parser.doesHeaderMatch("<ConcatenatedResource>/some/dir".getBytes()));
		Assert.assertTrue(parser.doesHeaderMatch("<ConcatenatedResource>".getBytes()));
	}

	/**
	 * A header that lacks the magic prefix must not match.
	 */
	@Test(timeout = 5000)
	public void headerParserRejectsOtherContent() {
		ResourceHeaderParser parser = new ConcatenatedResource.ConcatenatedResourceHeaderParser();
		Assert.assertFalse(parser.doesHeaderMatch("plain file bytes".getBytes()));
		Assert.assertFalse(parser.doesHeaderMatch("<OtherResource>".getBytes()));
	}

	/**
	 * The parser must resolve to the {@link ConcatenatedResource} class so the
	 * framework instantiates the correct concrete type.
	 */
	@Test(timeout = 5000)
	public void headerParserResolvesConcatenatedClass() {
		ResourceHeaderParser parser = new ConcatenatedResource.ConcatenatedResourceHeaderParser();
		Assert.assertEquals(ConcatenatedResource.class, parser.getResourceClass());
	}
}
