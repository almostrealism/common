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
		String scalar = "{\"v\":\"a\\/b\\b\\fc\"}";
		String array = "{\"v\":[\"a\\/b\\b\\fc\"]}";

		String fromScalar = JsonFieldExtractor.extractString(scalar, "v");
		List<String> fromArray = JsonFieldExtractor.extractStringArray(array, "v");

		Assert.assertEquals("a/b\b\fc", fromScalar);
		Assert.assertEquals(1, fromArray.size());
		Assert.assertEquals(fromScalar, fromArray.get(0));
	}

	/**
	 * A {@code ']'} inside a string element is a literal character, not an
	 * array terminator — {@code ["ssh://git@[::1]/repo.git", "other"]} is
	 * valid JSON. Locating the array end with the first {@code ']'} (as the
	 * old implementation did) truncates the list and corrupts the element
	 * that carries the bracket, which is exactly what an IPv6 git URL in
	 * {@code dependentRepos} would trigger.
	 */
	@Test(timeout = 10000)
	public void arrayElementContainingCloseBracket() {
		String json = "{\"dependentRepos\":[\"ssh://git@[::1]/repo.git\", \"other\"]}";

		List<String> values = JsonFieldExtractor.extractStringArray(json, "dependentRepos");

		Assert.assertEquals(2, values.size());
		Assert.assertEquals("ssh://git@[::1]/repo.git", values.get(0));
		Assert.assertEquals("other", values.get(1));
	}

	/**
	 * A {@code ']'} reached via an escaped quote must still be treated as
	 * being inside the string: the escape closes over the quote, not the
	 * string, so {@code inString} must remain {@code true} through the
	 * {@code ']'} that follows it.
	 */
	@Test(timeout = 10000)
	public void arrayElementContainingEscapedQuoteAndBracket() {
		String json = "{\"v\":[\"a\\\"]b\", \"c\"]}";

		List<String> values = JsonFieldExtractor.extractStringArray(json, "v");

		Assert.assertEquals(2, values.size());
		Assert.assertEquals("a\"]b", values.get(0));
		Assert.assertEquals("c", values.get(1));
	}

	/**
	 * An array with no closing bracket has no match for
	 * {@link JsonFieldExtractor#extractStringArray(String, String)} to find,
	 * so it must return an empty list rather than throwing or scanning past
	 * the end of the input.
	 */
	@Test(timeout = 10000)
	public void unclosedArrayReturnsEmptyList() {
		String json = "{\"v\":[\"a\", \"b\"";

		List<String> values = JsonFieldExtractor.extractStringArray(json, "v");

		Assert.assertTrue(values.isEmpty());
	}

	/**
	 * {@link JsonFieldExtractor#countArrayEntries(String, String)} shares the
	 * same {@code matchingBracket} array-end lookup as
	 * {@link JsonFieldExtractor#extractStringArray(String, String)}, so a
	 * {@code ']'} inside a string field of one of the counted objects must
	 * not be mistaken for the array's own closing bracket.
	 */
	@Test(timeout = 10000)
	public void countArrayEntriesWithBracketInStringField() {
		String json = "{\"items\":[{\"url\":\"ssh://git@[::1]/repo.git\"},{\"url\":\"b\"}]}";

		int count = JsonFieldExtractor.countArrayEntries(json, "items");

		Assert.assertEquals(2, count);
	}

	/**
	 * {@link JsonFieldExtractor#extractFieldFromArrayObjects(String, String, String)}
	 * also shares {@code matchingBracket}; a {@code ']'} inside a sibling
	 * field's string value must not truncate the array before every object
	 * is visited.
	 */
	@Test(timeout = 10000)
	public void extractFieldFromArrayObjectsWithBracketInOtherField() {
		String json = "{\"items\":[{\"name\":\"a\",\"url\":\"ssh://git@[::1]/repo.git\"},"
				+ "{\"name\":\"b\",\"url\":\"c\"}]}";

		List<String> names = JsonFieldExtractor.extractFieldFromArrayObjects(json, "items", "name");

		Assert.assertEquals(2, names.size());
		Assert.assertEquals("a", names.get(0));
		Assert.assertEquals("b", names.get(1));
	}
}
