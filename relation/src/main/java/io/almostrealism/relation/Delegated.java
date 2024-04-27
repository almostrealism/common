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

package io.almostrealism.relation;

import java.util.ArrayList;
import java.util.List;

public interface Delegated<T> {
	T getDelegate();

	default T getRootDelegate() {
		if (getDelegate() == null) return (T) this;
		if (getDelegate() instanceof Delegated) return (T) ((Delegated) getDelegate()).getRootDelegate();
		return getDelegate();
	}

	default int getDelegateDepth() {
		if (getDelegate() == null) return 0;
		if (getDelegate() instanceof Delegated) return 1 + ((Delegated) getDelegate()).getDelegateDepth();
		return 1;
	}

	default void validateDelegate() {
		validateDelegate(new ArrayList<>());
	}

	default void validateDelegate(List<T> existing) {
		if (getDelegate() == null) return;

		if (existing.contains(getDelegate())) {
			throw new IllegalStateException("Circular delegation detected");
		}

		existing.add(getDelegate());
		if (getDelegate() instanceof Delegated) ((Delegated) getDelegate()).validateDelegate(existing);
	}
}
