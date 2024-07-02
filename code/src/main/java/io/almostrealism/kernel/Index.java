/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.kernel;

import io.almostrealism.uml.Named;

import java.util.OptionalLong;

public interface Index extends SequenceGenerator, Named {

	static IndexChild child(Index parent, Index child) {
		return child(parent, child, null);
	}

	static IndexChild child(Index parent, Index child, Long limitMax) {
		IndexChild result;

		if (parent instanceof KernelIndex) {
			result = new KernelIndexChild(((KernelIndex) parent).getContext(), child);
		} else {
			result = new IndexChild(parent, child);
		}

		if (limitMax != null) {
			OptionalLong limit = result.getLimit();

			if (limit.isEmpty() || limit.getAsLong() > limitMax) {
				return null;
			}
		}

		return result;
	}
}
