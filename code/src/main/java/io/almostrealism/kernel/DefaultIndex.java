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

import io.almostrealism.expression.StaticReference;

import java.util.Objects;
import java.util.OptionalLong;

public class DefaultIndex extends StaticReference<Integer> implements Index {
	private OptionalLong limit;

	public DefaultIndex(String name) {
		this(name, null);
	}

	public DefaultIndex(String name, int limit) {
		this(name, (long) limit);
	}

	public DefaultIndex(String name, Long limit) {
		super(Integer.class, name);
		this.limit = limit == null ? OptionalLong.empty() : OptionalLong.of(limit);
	}

	public void setLimit(int limit) { this.limit = OptionalLong.of(limit); }

	@Override
	public OptionalLong getLimit() {
		if (limit.isPresent()) {
			return limit;
		}

		OptionalLong upperBound = upperBound(null);
		if (upperBound.isPresent()) {
			return OptionalLong.of(upperBound.getAsLong() + 1);
		}

		return OptionalLong.empty();
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		return limit.stream().map(i -> i - 1).findFirst();
	}

	@Override
	public boolean isValue(IndexValues values) {
		return values.containsIndex(getName());
	}

	@Override
	public Number value(IndexValues indexValues) {
		return indexValues.getIndex(getName());
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof DefaultIndex && Objects.equals(((DefaultIndex) obj).getName(), getName());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getName());
	}
}
