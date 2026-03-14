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

package org.almostrealism.persist.test;

import org.almostrealism.collect.PackedCollection;

import java.io.File;

/**
 * Shared utility methods for disk store tests. Consolidates helpers
 * that are used by both {@link ProtobufDiskStoreTest} and
 * {@link VectorSearchTest}.
 */
final class DiskStoreTestSupport {

	private DiskStoreTestSupport() { }

	/**
	 * Build a {@link TestRecordProto.TestRecord} with the given fields.
	 *
	 * @param id      record identifier
	 * @param content text content
	 * @param value   integer value
	 * @return a new test record
	 */
	static TestRecordProto.TestRecord makeRecord(String id, String content, int value) {
		return TestRecordProto.TestRecord.newBuilder()
				.setId(id)
				.setContent(content)
				.setValue(value)
				.build();
	}

	/**
	 * Create a {@link PackedCollection} from the given values.
	 *
	 * @param values the vector components
	 * @return a new PackedCollection containing the values
	 */
	static PackedCollection vec(double... values) {
		return new PackedCollection(values.length).fill(values);
	}

	/**
	 * Recursively delete a file or directory tree.
	 *
	 * @param file the root to delete
	 */
	static void deleteRecursively(File file) {
		if (file == null || !file.exists()) return;
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}
		file.delete();
	}
}
