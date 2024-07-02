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

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;
import io.almostrealism.lang.LanguageOperations;

import java.util.OptionalLong;

public class IndexChild extends Sum<Integer> implements Index {
	private Index parent;
	private Index childIndex;
	private boolean renderAlias;
	private String name;

	public IndexChild(Index parent, Index childIndex) {
		super((Expression)
						Product.of((Expression) parent,
								new IntegerConstant(Math.toIntExact(childIndex.getLimit().getAsLong()))),
				(Expression) childIndex);
		this.parent = parent;
		this.childIndex = childIndex;
	}

	protected String initName() {
		return parent.getName() + "_" + childIndex.getName();
	}

	@Override
	public String getName() {
		if (name == null) name = initName();
		return name;
	}

	public Index getChildIndex() {
		return childIndex;
	}

	public void setRenderAlias(boolean renderAlias) {
		this.renderAlias = renderAlias;
	}

	public IndexChild renderAlias() {
		setRenderAlias(true);
		return new IndexChild(parent, childIndex);
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalLong max = parent.upperBound(context);
		if (!max.isPresent()) return OptionalLong.empty();

		OptionalLong limit = childIndex.getLimit();
		if (!limit.isPresent()) return OptionalLong.empty();

		return OptionalLong.of((max.getAsLong() + 1) * limit.getAsLong() - 1);
	}

	@Override
	public boolean isValue(IndexValues values) {
		if (values.containsIndex(getName())) return true;
		return super.isValue(values);
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
	public Expression simplify(KernelStructureContext context) {
		return this;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof IndexChild)) return false;
		return ((IndexChild) o).childIndex.equals(childIndex);
	}

	@Override
	public int hashCode() {
		return childIndex.hashCode();
	}
}
