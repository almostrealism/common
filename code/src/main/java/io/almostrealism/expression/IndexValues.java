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

package io.almostrealism.expression;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

// TODO  Move to kernel package
public class IndexValues {
	private Integer kernelIndex;
	private Map<String, Integer> values;

	public IndexValues() { this((Integer) null); }

	public IndexValues(IndexValues from) {
		this.kernelIndex = from.kernelIndex;
		this.values = new HashMap<>(from.values);
	}

	public IndexValues(Integer kernelIndex) {
		this.kernelIndex = kernelIndex;
		this.values = new HashMap<>();
	}

	public Integer getKernelIndex() { return kernelIndex; }

	public Integer getIndex(String name) {
		return values.get(name);
	}

	public boolean containsIndex(String name) {
		return values.containsKey(name);
	}

	public IndexValues addIndex(String name, Integer index) {
		values.put(name, index);
		return this;
	}

	public IndexValues put(Index idx, Integer value) {
		if (idx instanceof KernelIndex) {
			kernelIndex = value;
		} else {
			values.put(idx.getName(), value);

			if (idx instanceof KernelIndexChild) {
				int ki = ((KernelIndexChild) idx).kernelIndex(value.intValue());

				if (kernelIndex == null) {
					kernelIndex = ki;
				} else if (kernelIndex != ki) {
					throw new IllegalArgumentException("Kernel index mismatch");
				}
			}
		}

		return this;
	}

	public Expression apply(Expression exp) {
		for (Map.Entry<String, Integer> entry : values.entrySet()) {
			exp = exp.withValue(entry.getKey(), entry.getValue());
		}

		if (kernelIndex != null) {
			exp = exp.withIndex(new KernelIndex(), kernelIndex);
		}

		return exp;
	}

	public static IndexValues of(Collection<Index> indices) {
		IndexValues values = new IndexValues();
		indices.stream()
				.filter(idx -> !(idx instanceof KernelIndex))
				.forEach(idx -> values.addIndex(idx.getName(), 0));
		return values;
	}
}
