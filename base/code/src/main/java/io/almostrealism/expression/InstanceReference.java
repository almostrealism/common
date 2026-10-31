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

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * {@link InstanceReference} is used to reference a previously declared
 * {@link Variable}. {@link CodePrintWriter} implementations should
 * encode the data as a {@link String}, but unlike a normal {@link String}
 * {@link Variable} the text does not appear in quotes.
 */
public class InstanceReference<T, V> extends Expression<V> implements ExpressionFeatures, ConsoleFeatures {

	/** The variable being referenced. */
	private Variable<T, ?> var;

	/** The position expression used to index into the variable, or {@code null} for a scalar reference. */
	private Expression<?> pos;

	/** An optional supplementary index expression used for multi-dimensional access. */
	private Expression<?> index;

	/**
	 * Constructs a scalar reference to the given variable with no position expression.
	 *
	 * @param referent the variable to reference
	 */
	public InstanceReference(Variable<T, ?> referent) {
		super((Class) referent.getType());
		this.var = referent;
		init();
	}

	/**
	 * Constructs an array-element reference to the given variable at the specified position.
	 *
	 * @param referent the variable to reference
	 * @param pos      the position expression for array indexing
	 * @param index    a supplementary index expression, or {@code null}
	 */
	public InstanceReference(Variable<T, ?> referent, Expression<?> pos, Expression<?> index) {
		super((Class) referent.getType(), false, pos);
		this.var = referent;
		this.pos = pos;
		this.index = index;
		init();
	}

	@Override
	protected void init() {
		if (getReferent() == null ||
				(getReferent().getDelegate() == null && getReferent().getName() == null)) {
			throw new UnsupportedOperationException();
		}

		super.init();
	}

	public Variable<T, ?> getReferent() { return var; }
	public Expression<?> getIndex() { return index; }

	@Override
	public boolean isVariableReference() { return true; }

	/** {@inheritDoc} The referent renders the reference, at this reference's position. */
	@Override
	public String getExpression(LanguageOperations lang) {
		return var.referenceExpression(lang, pos);
	}

	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	@Override
	public List<Variable<?, ?>> getDependencies() {
		if (var == null) return super.getDependencies();

		ArrayList<Variable<?, ?>> dependencies = new ArrayList<>();
		dependencies.add(var);
		dependencies.addAll(super.getDependencies());
		return dependencies;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Variables are compared by {@link Variable#hasSameRoot(Variable) their root}, because a
	 * reference to a variable that delegates to another is created as a reference to the
	 * delegate. A view of {@code var} and {@code var} itself, or two views of the same storage,
	 * are therefore treated as the same variable.</p>
	 */
	@Override
	public boolean containsReference(Variable var) {
		return getReferent().hasSameRoot(var) || super.containsReference(var);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>An assignment to a reference writes exactly one element of its referent, which is
	 * {@code array} when they {@link Variable#hasSameRoot(Variable) share a root}, as for
	 * {@link #containsReference(Variable)}. The element is at this reference's
	 * position, before the referent's own offset is applied when it is rendered, so the
	 * written position lies between the {@link #lowerBound lower} and {@link #upperBound upper}
	 * bounds of the position; a bound that cannot be determined leaves that side of the range
	 * open, and a reference to the whole referent may write any of it.</p>
	 */
	@Override
	public boolean mayAssign(Variable<?, ?> array, long start, long end) {
		if (!getReferent().hasSameRoot(array)) return false;
		if (pos == null) return true;

		OptionalLong lower = pos.lowerBound(null);
		OptionalLong upper = pos.upperBound(null);
		return (upper.isEmpty() || upper.getAsLong() >= start)
				&& (lower.isEmpty() || lower.getAsLong() < end);
	}

	@Override
	public boolean containsReference(Set<String> names) {
		return (getReferent() != null && names.contains(getReferent().getName())) || super.containsReference(names);
	}

	@Override
	public ExpressionAssignment<V> assign(Expression exp) {
		return new ExpressionAssignment<>(this, exp);
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		return deltaUnlessTarget(target, constantZero(target.getShape()));
	}

	/** {@inheritDoc} Recreates the reference using the same variable but new position children. */
	@Override
	public InstanceReference<T, V> recreate(List<Expression<?>> children) {
		if (children.isEmpty()) {
			return new InstanceReference<>(var);
		} else if (children.size() == 1) {
			return new InstanceReference<>(var, children.get(0), index);
		} else {
			throw new UnsupportedOperationException();
		}
	}

	@Override
	public boolean compare(Expression e) {
		if (this == e) return true;
		if (!(e instanceof InstanceReference)) return false;

		InstanceReference<?, ?> alt = (InstanceReference<?, ?>) e;
		return Objects.equals(var, alt.var) && Objects.equals(pos, alt.pos) && Objects.equals(index, alt.index);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>A reference matches another reference whose referent {@link Variable#isSameAs is the
	 * same} as its own, wherever their supplementary indices are equal. When neither reference
	 * has a supplementary index, their positions are compared instead, and two references to
	 * the variable as a whole (with no position) match. It never matches any other kind of
	 * expression.</p>
	 */
	@Override
	public Expression<Boolean> matches(Expression<?> other) {
		if (!(other instanceof InstanceReference)) return new BooleanConstant(false);

		InstanceReference<?, ?> ref = (InstanceReference<?, ?>) other;
		if (!getReferent().isSameAs(ref.getReferent())) return new BooleanConstant(false);
		if (index != null && ref.index != null) return index.eq(ref.index);
		if (index != null || ref.index != null) return new BooleanConstant(false);
		if (pos == null || ref.pos == null) return new BooleanConstant(pos == ref.pos);

		return pos.eq(ref.pos);
	}
}
