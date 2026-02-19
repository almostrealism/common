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

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight JSON field extraction utilities that avoid external library dependencies.
 *
 * <p>These methods extract primitive values from JSON strings using simple
 * string parsing. They are designed for cases where a full JSON parser
 * is not available or warranted (e.g., parsing CLI output fragments).</p>
 *
 * <p>These are intentionally simple and do not handle all JSON edge cases.
 * For complex JSON processing, use a proper JSON library instead.</p>
 */
public final class JsonFieldExtractor {

	private JsonFieldExtractor() { }

	/**
	 * Extracts a string field from a JSON string.
	 * Handles JSON escape sequences including Unicode escapes.
	 *
	 * @param json  the JSON string
	 * @param field the field name
	 * @return the string value, or null if not found
	 */
	public static String extractString(String json, String field) {
		if (json == null) return null;

		int fieldStart = json.indexOf("\"" + field + "\"");
		if (fieldStart < 0) return null;

		int colonPos = json.indexOf(":", fieldStart);
		if (colonPos < 0) return null;

		int afterColon = colonPos + 1;
		while (afterColon < json.length() && json.charAt(afterColon) == ' ') {
			afterColon++;
		}

		if (afterColon + 4 <= json.length() &&
				json.substring(afterColon, afterColon + 4).equals("null")) {
			return null;
		}

		int valueStart = json.indexOf("\"", colonPos) + 1;
		if (valueStart <= 0) return null;

		StringBuilder sb = new StringBuilder();
		for (int i = valueStart; i < json.length(); i++) {
			char c = json.charAt(i);
			if (c == '\\' && i + 1 < json.length()) {
				char next = json.charAt(i + 1);
				if (next == '"') { sb.append('"'); i++; }
				else if (next == '\\') { sb.append('\\'); i++; }
				else if (next == 'n') { sb.append('\n'); i++; }
				else if (next == 'r') { sb.append('\r'); i++; }
				else if (next == 't') { sb.append('\t'); i++; }
				else if (next == 'b') { sb.append('\b'); i++; }
				else if (next == 'f') { sb.append('\f'); i++; }
				else if (next == '/') { sb.append('/'); i++; }
				else if (next == 'u' && i + 5 < json.length()) {
					String hex = json.substring(i + 2, i + 6);
					try {
						sb.append((char) Integer.parseInt(hex, 16));
						i += 5;
					} catch (NumberFormatException e) {
						sb.append(c);
					}
				} else {
					sb.append(c);
				}
			} else if (c == '"') {
				break;
			} else {
				sb.append(c);
			}
		}

		return sb.toString();
	}

	/**
	 * Extracts a boolean field from a JSON string.
	 *
	 * @param json  the JSON string
	 * @param field the field name
	 * @return the boolean value, or false if not found
	 */
	public static boolean extractBoolean(String json, String field) {
		if (json == null) return false;

		int fieldStart = json.indexOf("\"" + field + "\"");
		if (fieldStart < 0) return false;

		int colonPos = json.indexOf(":", fieldStart);
		if (colonPos < 0) return false;

		String rest = json.substring(colonPos + 1).trim();
		return rest.startsWith("true");
	}

	/**
	 * Extracts an integer field from a JSON string.
	 *
	 * @param json  the JSON string
	 * @param field the field name
	 * @return the integer value, or 0 if not found
	 */
	public static int extractInt(String json, String field) {
		if (json == null) return 0;

		int fieldStart = json.indexOf("\"" + field + "\"");
		if (fieldStart < 0) return 0;

		int colonPos = json.indexOf(":", fieldStart);
		if (colonPos < 0) return 0;

		String numString = extractNumericString(json.substring(colonPos + 1).trim(), false);
		try {
			return Integer.parseInt(numString);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * Extracts a long field from a JSON string.
	 *
	 * @param json  the JSON string
	 * @param field the field name
	 * @return the long value, or 0 if not found
	 */
	public static long extractLong(String json, String field) {
		if (json == null) return 0;

		int fieldStart = json.indexOf("\"" + field + "\"");
		if (fieldStart < 0) return 0;

		int colonPos = json.indexOf(":", fieldStart);
		if (colonPos < 0) return 0;

		String numString = extractNumericString(json.substring(colonPos + 1).trim(), false);
		try {
			return Long.parseLong(numString);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * Extracts a double field from a JSON string.
	 *
	 * @param json  the JSON string
	 * @param field the field name
	 * @return the double value, or 0.0 if not found
	 */
	public static double extractDouble(String json, String field) {
		if (json == null) return 0.0;

		int fieldStart = json.indexOf("\"" + field + "\"");
		if (fieldStart < 0) return 0.0;

		int colonPos = json.indexOf(":", fieldStart);
		if (colonPos < 0) return 0.0;

		String numString = extractNumericString(json.substring(colonPos + 1).trim(), true);
		try {
			return Double.parseDouble(numString);
		} catch (NumberFormatException e) {
			return 0.0;
		}
	}

	/**
	 * Extracts a JSON array of strings from a JSON string.
	 *
	 * @param json  the JSON string
	 * @param field the field name
	 * @return list of string values from the array
	 */
	public static List<String> extractStringArray(String json, String field) {
		List<String> result = new ArrayList<>();
		if (json == null) return result;

		int fieldStart = json.indexOf("\"" + field + "\"");
		if (fieldStart < 0) return result;

		int colonPos = json.indexOf(":", fieldStart);
		if (colonPos < 0) return result;

		int arrayStart = json.indexOf("[", colonPos);
		if (arrayStart < 0) return result;

		int arrayEnd = json.indexOf("]", arrayStart);
		if (arrayEnd < 0) return result;

		String arrayContent = json.substring(arrayStart + 1, arrayEnd);
		if (arrayContent.trim().isEmpty()) return result;

		int i = 0;
		while (i < arrayContent.length()) {
			int quoteStart = arrayContent.indexOf("\"", i);
			if (quoteStart < 0) break;

			StringBuilder value = new StringBuilder();
			int j = quoteStart + 1;
			while (j < arrayContent.length()) {
				char c = arrayContent.charAt(j);
				if (c == '\\' && j + 1 < arrayContent.length()) {
					char next = arrayContent.charAt(j + 1);
					if (next == '"') { value.append('"'); j += 2; }
					else if (next == '\\') { value.append('\\'); j += 2; }
					else if (next == 'n') { value.append('\n'); j += 2; }
					else if (next == 'r') { value.append('\r'); j += 2; }
					else if (next == 't') { value.append('\t'); j += 2; }
					else if (next == 'u' && j + 5 < arrayContent.length()) {
						String hex = arrayContent.substring(j + 2, j + 6);
						try {
							value.append((char) Integer.parseInt(hex, 16));
							j += 6;
						} catch (NumberFormatException e) {
							value.append(c);
							j++;
						}
					} else {
						value.append(c);
						j++;
					}
				} else if (c == '"') {
					break;
				} else {
					value.append(c);
					j++;
				}
			}

			result.add(value.toString());
			i = j + 1;
		}

		return result;
	}

	/**
	 * Counts the number of object entries in a JSON array field.
	 *
	 * @param json  the JSON string
	 * @param field the field name
	 * @return the number of top-level objects in the array, or 0 if not found
	 */
	public static int countArrayEntries(String json, String field) {
		if (json == null) return 0;

		int fieldIdx = json.indexOf("\"" + field + "\"");
		if (fieldIdx < 0) return 0;

		int colonIdx = json.indexOf(":", fieldIdx);
		if (colonIdx < 0) return 0;

		int arrStart = json.indexOf("[", colonIdx);
		if (arrStart < 0) return 0;

		int arrEnd = -1;
		int depth = 1;
		for (int i = arrStart + 1; i < json.length() && depth > 0; i++) {
			char c = json.charAt(i);
			if (c == '[') depth++;
			else if (c == ']') {
				depth--;
				if (depth == 0) arrEnd = i;
			}
		}

		if (arrEnd < 0) return 0;

		String arrContent = json.substring(arrStart + 1, arrEnd).trim();
		if (arrContent.isEmpty()) return 0;

		int count = 0;
		int braceDepth = 0;
		for (int i = 0; i < arrContent.length(); i++) {
			char c = arrContent.charAt(i);
			if (c == '{' && braceDepth == 0) count++;
			if (c == '{') braceDepth++;
			else if (c == '}') braceDepth--;
		}

		return count;
	}

	/**
	 * Extracts a numeric string from the beginning of the input.
	 *
	 * @param rest         the string to extract from (already trimmed after the colon)
	 * @param allowDecimal whether to include decimal point characters
	 * @return the extracted numeric string
	 */
	private static String extractNumericString(String rest, boolean allowDecimal) {
		StringBuilder numStr = new StringBuilder();
		for (int i = 0; i < rest.length(); i++) {
			char c = rest.charAt(i);
			if (c == '-' || (c >= '0' && c <= '9') || (allowDecimal && c == '.')) {
				numStr.append(c);
			} else if (numStr.length() > 0) {
				break;
			}
		}
		return numStr.toString();
	}
}
