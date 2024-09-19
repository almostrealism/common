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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.lang.LanguageOperations;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public abstract class Constant<T> extends Expression<T> {
	public static boolean enableNegationOptimization = true;

	public Constant(Class<T> type) {
		super(type);
	}

	@Override
	public boolean isValue(IndexValues values) { return true; }

	@Override
	public Set<Index> getIndices() {
		return Collections.emptySet();
	}

	@Override
	public boolean containsIndex(Index idx) { return false; }

	@Override
	public String getExpression(LanguageOperations lang) { return null; }

	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	public Constant<T> recreate(List<Expression<?>> children) {
		if (children.size() > 0) {
			throw new UnsupportedOperationException();
		}

		return this;
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		return new ConstantCollectionExpression(target.getShape(), new IntegerConstant(0));
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Constant)) {
			return false;
		}

		return Objects.equals(((Constant<?>) obj).getType(), getType()) &&
				Objects.equals(((Constant<?>) obj).getValue(), getValue());
	}

	@Override
	public int hashCode() {
		return String.valueOf(getValue()).hashCode();
	}

	public static <T> Constant<T> of(T value) {
		if (value instanceof Integer) {
			return (Constant<T>) new IntegerConstant((Integer) value);
		} else if (value instanceof Long) {
			return (Constant<T>) new LongConstant((Long) value);
		} else if (value instanceof Double) {
			return (Constant<T>) new DoubleConstant((Double) value);
		} else if (value instanceof Boolean) {
			return (Constant<T>) new BooleanConstant((Boolean) value);
		} else {
			return new ConstantValue(value.getClass(), value);
		}
	}

	public static <T> Constant<T> forType(Class<T> type) {
		return new ConstantValue<>(type, null);
	}
}
