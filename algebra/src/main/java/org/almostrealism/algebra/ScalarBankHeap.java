/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.algebra;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A collection of {@link ScalarBank}s stored in a single {@link io.almostrealism.code.Memory} instance.
 */
@Deprecated
public class ScalarBankHeap {
	private List<ScalarBank> entries;
	private ScalarBank data;
	private int end;

	public ScalarBankHeap(int totalSize) {
		entries = new ArrayList<>();
		data = new ScalarBank(totalSize);
	}

	public synchronized ScalarBank allocate(int count) {
		if (end + count > data.getCount()) {
			throw new IllegalArgumentException("No room remaining in ScalarBankHeap");
		}

		ScalarBank allocated = new ScalarBank(count, data, 2 * end);
		end = end + count;
		entries.add(allocated);
		return allocated;
	}

	public ScalarBank add(ScalarBank toImport) {
		ScalarBank imported = allocate(toImport.getCount());
		imported.copyFromVec(toImport);
		return imported;
	}

	public ScalarBank get(int index) { return entries.get(index); }

	public Stream<ScalarBank> stream() { return entries.stream(); }

	public synchronized void destroy() {
		entries.clear();
		end = 0;
		data.destroy();
	}
}
