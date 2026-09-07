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

package io.flowtree;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests for the JSON escape decoding performed by {@link JsonFieldExtractor}.
 *
 * <p>{@link JsonFieldExtractor#extractString(String, String)} and
 * {@link JsonFieldExtractor#extractStringArray(String, String)} both walk a
 * quoted JSON string value and translate backslash escape sequences. They must
 * decode the same set of escapes: a value read from a scalar field and the same
 * value read from an array element are the same JSON string and must come back
 * identical.</p>
 */
public class JsonFieldExtractorTest extends TestSuiteBase {

	/**
	 * The escaped-solidus sequence {@code \/} is a legal JSON escape for
	 * {@code /} (RFC 8259), and many producers emit it. Reading such a value
	 * from an array element must yield the unescaped {@code /}, matching what
	 * {@link JsonFieldExtractor#extractString(String, String)} already does for
	 * a scalar field.
	 */
	@Test(timeout = 10000)
	public void arrayElementDecodesEscapedSolidus() {
		String json = "{\"listeners\":[\"https:\\/\\/hook.example.com\\/notify\"]}";

		List<String> values = JsonFieldExtractor.extractStringArray(json, "listeners");

		Assert.assertEquals(1, values.size());
		Assert.assertEquals("https://hook.example.com/notify", values.get(0));
	}

	/**
	 * The array decoder and the scalar decoder must agree on the same value.
	 * {@code extractString} decodes {@code \/}, {@code \b}, and {@code \f}; the
	 * array decoder must decode them identically rather than leaving stray
	 * backslashes behind.
	 */
	@Test(timeout = 10000)
	public void arrayAndScalarDecodersAgree() {
		String scalar = "{\"v\":\"a\\/b\"}";
		String array = "{\"v\":[\"a\\/b\"]}";

		String fromScalar = JsonFieldExtractor.extractString(scalar, "v");
		List<String> fromArray = JsonFieldExtractor.extractStringArray(array, "v");

		Assert.assertEquals("a/b", fromScalar);
		Assert.assertEquals(1, fromArray.size());
		Assert.assertEquals(fromScalar, fromArray.get(0));
	}
}
