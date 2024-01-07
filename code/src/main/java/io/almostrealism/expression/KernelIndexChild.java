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

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalInt;

public class KernelIndexChild extends Sum<Integer> implements Index {
	private DefaultIndex childIndex;
	private boolean renderAlias;

	public KernelIndexChild(DefaultIndex childIndex) {
		super((Expression)
						Product.of(new KernelIndex(),
							new IntegerConstant(childIndex.getLimit().getAsInt())),
				childIndex);
		this.childIndex = childIndex;
	}

	@Override
	public String getName() {
		return "k" + childIndex.getName();
	}

	public void setRenderAlias(boolean renderAlias) {
		this.renderAlias = renderAlias;
	}

	public KernelIndexChild renderAlias() {
		setRenderAlias(true);
		return new KernelIndexChild(childIndex);
	}

	@Override
	public OptionalInt getLimit() { return OptionalInt.empty(); }

	public int kernelIndex(int index) {
		return index / childIndex.getLimit().getAsInt();
	}

	@Override
	public OptionalInt upperBound(KernelStructureContext context) {
		OptionalInt max = context.getKernelMaximum();
		if (!max.isPresent()) return OptionalInt.empty();

		OptionalInt limit = childIndex.getLimit();
		if (!limit.isPresent()) return OptionalInt.empty();

		return OptionalInt.of(max.getAsInt() * limit.getAsInt() - 1);
	}

	@Override
	public boolean isKernelValue(IndexValues values) {
		if (values.containsIndex(getName())) return true;
		return super.isKernelValue(values);
	}

	@Override
	public Number value(IndexValues indexValues) {
		if (indexValues.containsIndex(getName())) {
			return indexValues.getIndex(getName());
		}

		return super.value(indexValues);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		if (renderAlias) return getName();
		return super.getExpression(lang);
	}

	@Override
	public Expression<Integer> generate(List<Expression<?>> children) {
		return this;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof KernelIndexChild)) return false;
		return ((KernelIndexChild) o).childIndex.equals(childIndex);
	}

	@Override
	public int hashCode() {
		return childIndex.hashCode();
	}
}
