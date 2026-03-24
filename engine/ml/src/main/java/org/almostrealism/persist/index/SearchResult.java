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

package org.almostrealism.persist.index;

/**
 * A single result from a vector similarity search, containing the
 * record ID, the full record, and the similarity score.
 *
 * @param <T> the record type
 */
public class SearchResult<T> {
	private final String id;
	private final T record;
	private final float similarity;

	/**
	 * Create a search result.
	 *
	 * @param id         record identifier
	 * @param record     full record
	 * @param similarity similarity score
	 */
	public SearchResult(String id, T record, float similarity) {
		this.id = id;
		this.record = record;
		this.similarity = similarity;
	}

	/** Return the record identifier. */
	public String getId() {
		return id;
	}

	/** Return the full record. */
	public T getRecord() {
		return record;
	}

	/** Return the similarity score. */
	public float getSimilarity() {
		return similarity;
	}
}
