/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.api;

import java.util.List;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;

/**
 * Reads query parameters off a request.
 *
 * <p>NanoHTTPD hands back a multi-valued map in which a parameter sent with no
 * value is present with an empty string. A caller assembling a query from
 * optional filters sends those routinely, so the distinction between "absent"
 * and "present but empty" is a distinction without a difference here — and
 * treating an empty value as a filter would match nothing at all.</p>
 */
final class RequestParameters {

	/** Not instantiable: this type is a namespace for the reader below. */
	private RequestParameters() { }

	/**
	 * Returns a parameter's first value, treating an empty value as absent.
	 *
	 * @param session      the request to read
	 * @param name         the parameter name
	 * @param defaultValue the value to return when the parameter is absent or
	 *                     empty; may be {@code null}
	 * @return the parameter's first non-empty value, or {@code defaultValue}
	 */
	static String first(IHTTPSession session, String name, String defaultValue) {
		List<String> values = session.getParameters().get(name);
		if (values == null || values.isEmpty()) return defaultValue;
		String value = values.get(0);
		return value == null || value.isEmpty() ? defaultValue : value;
	}
}
