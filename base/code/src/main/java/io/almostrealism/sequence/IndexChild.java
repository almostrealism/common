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

package io.almostrealism.sequence;

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;
import io.almostrealism.lang.LanguageOperations;

import java.util.OptionalLong;

/**
 * A composite {@link Index} representing a multi-dimensional position encoded as a flat integer.
 *
 * <p>{@code IndexChild} encodes a two-level index hierarchy (parent, child) as:
 * {@code parent * childLimit + child}. This is used when a computation iterates over
 * a multi-dimensional space that must be mapped to a single kernel thread dimension.</p>
 *
 * <p>The expression form is a sum: {@link Sum}({@code parent * childLimit}, {@code child}).
 *
 * @see Index
 * @see DefaultIndex
 * @see io.almostrealism.kernel.KernelIndexChild
 */
public class IndexChild extends Sum<Integer> implements Index {
	/** The outer (parent) dimension of this composite index. */
	private Index parent;
	/** The inner (child) dimension of this composite index. */
	private Index childIndex;
	/** Whether to render this index as its alias name instead of the sum expression. */
	private boolean renderAlias;
	/** The cached name for this index. */
	private String name;

	/**
	 * Creates a composite index encoding {@code parent * childLimit + child}.
	 *
	 * @param parent the outer (higher-order) index
	 * @param childIndex the inner (lower-order) index; its limit must be present
	 */
	public IndexChild(Index parent, Index childIndex) {
		super((Expression)
						Product.of((Expression) parent,
								new IntegerConstant(Math.toIntExact(childIndex.getLimit().getAsLong()))),
				(Expression) childIndex);
		this.parent = parent;
		this.childIndex = childIndex;
	}

	/**
	 * Initializes the name for this composite index from the parent and child names.
	 *
	 * @return the derived name in the form {@code "parentName_childName"}
	 */
	protected String initName() {
		return parent.getName() + "_" + childIndex.getName();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return the derived name, lazily initialized
	 */
	@Override
	public String getName() {
		if (name == null) name = initName();
		return name;
	}

	/**
	 * Returns the child (inner) index of this composite.
	 *
	 * @return the child index
	 */
	public Index getChildIndex() {
		return childIndex;
	}

	/**
	 * Sets whether to render this index as its alias name rather than the sum expression.
	 *
	 * @param renderAlias {@code true} to render as alias
	 * @deprecated Use the alias rendering only for specific debugging scenarios.
	 */
	@Deprecated
	public void setRenderAlias(boolean renderAlias) {
		this.renderAlias = renderAlias;
	}

	/**
	 * Returns a new index child configured to render as an alias.
	 *
	 * @return a new alias-rendering index child
	 * @deprecated Use the alias rendering only for specific debugging scenarios.
	 */
	@Deprecated
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
	public Expression simplify(KernelStructureContext context, int depth) {
		return this;
	}

	@Override
	public boolean compare(Expression o) {
		if (!(o instanceof IndexChild)) return false;
		return ((IndexChild) o).childIndex.equals(childIndex);
	}

	@Override
	public int hashCode() {
		return childIndex.hashCode();
	}
}
