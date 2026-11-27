/*
 * Copyright 2026 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Mask;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.ReadPositions;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.scope.Variable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A statement that adds a value into an element of an array as one indivisible update:
 * {@code destination += value}, where concurrent updates of the same element by different
 * kernel threads all take effect.
 *
 * <p>An ordinary {@link ExpressionAssignment} of {@code destination = destination + value} is a
 * read followed by a write. When two kernel threads perform it on the same element at the same
 * time, both may read the old value, and one of the two additions is lost. That cannot happen in
 * a kernel where every thread owns the elements it writes, which is how almost every computation
 * is structured. A scatter, in which each thread owns a <em>source</em> and writes to positions
 * chosen at run time, has no such ownership, and its writes must be atomic.</p>
 *
 * <p>The statement is a property of the expression tree, not text spliced into generated code: it
 * is rendered by {@link LanguageOperations#atomicAccumulation(String, String)}, which each backend
 * implements with the indivisible update its language provides (a native atomic add, or a
 * compare-and-swap loop). A backend that cannot express one refuses to render the statement
 * rather than falling back to a read-modify-write.</p>
 *
 * <p>Because the order in which concurrent updates land is not defined, the accumulated value is
 * exact only when every partial sum is exactly representable at the precision of the destination,
 * such as integer-valued contributions whose partial sums stay within the range of exactly
 * representable integers ({@code 2^24} in single precision). Otherwise the result agrees with an
 * ordered sum to within floating-point rounding.</p>
 *
 * <p>The statement deliberately is not an {@link ExpressionAssignment}: optimizations that rewrite
 * an assignment, such as promoting a repeatedly updated element to a local accumulator, assume
 * the element is owned by the thread, which is exactly what an atomic accumulation does not
 * assume.</p>
 *
 * @param <T> the type of the accumulated value
 *
 * @see LanguageOperations#atomicAccumulation(String, String)
 */
public class AtomicAccumulation<T> implements Statement<AtomicAccumulation<T>> {
	/** The array element that receives the addition. */
	private final Expression<T> destination;

	/** The value added to the destination. */
	private final Expression<T> value;

	/**
	 * Creates an atomic accumulation of the given value into the given element.
	 *
	 * <p>A destination wrapped in a {@link Mask}, which is how
	 * {@link ArrayVariable#reference(Expression)} returns an element when
	 * {@link ScopeSettings#enableInstanceReferenceMasking} is set, accumulates into the masked
	 * element, exactly as {@link Mask#assign(Expression)} assigns to it.</p>
	 *
	 * @param destination the array element to accumulate into, which must be a
	 *                    {@link Expression#isVariableReference() variable reference}, or a
	 *                    {@link Mask} of one
	 * @param value       the value to add
	 * @throws IllegalArgumentException if either expression is {@code null}, or the destination is
	 *         not a reference to an element of an array
	 */
	public AtomicAccumulation(Expression<T> destination, Expression<T> value) {
		if (destination == null || value == null) {
			throw new IllegalArgumentException("An atomic accumulation needs a destination and a value");
		}

		// TODO(review): replace this instanceof Mask with a polymorphic Expression query (e.g. an accumulation target) that Mask overrides.
		if (destination instanceof Mask) {
			destination = ((Mask) destination).getMaskedValue();
		}

		if (!destination.isVariableReference()) {
			throw new IllegalArgumentException("Cannot atomically accumulate into " + destination);
		}

		this.destination = destination;
		this.value = value;
	}

	/**
	 * Returns the array element that receives the addition.
	 *
	 * @return the destination element
	 */
	public Expression<T> getDestination() { return destination; }

	/**
	 * Returns the value added to the destination.
	 *
	 * @return the added value
	 */
	public Expression<T> getValue() { return value; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Renders through {@link LanguageOperations#atomicAccumulation(String, String)}.</p>
	 */
	@Override
	public String getStatement(LanguageOperations lang) {
		return lang.atomicAccumulation(destination.getExpression(lang), value.getExpression(lang));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the union of the destination's and the value's dependencies.</p>
	 */
	@Override
	public List<Variable<?, ?>> getDependencies() {
		List<Variable<?, ?>> deps = new ArrayList<>();
		deps.addAll(destination.getDependencies());
		deps.addAll(value.getDependencies());
		return deps;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Whatever this statement writes, it writes through its destination, so the destination
	 * {@link Expression#containsAssignment(ArrayVariable, long, long) answers}.</p>
	 */
	@Override
	public boolean containsAssignment(ArrayVariable<?> array, long start, long end) {
		return destination.containsAssignment(array, start, end);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Whatever this statement changes, it changes through its destination.</p>
	 */
	@Override
	public void appendAssignedNames(Set<String> names) {
		destination.appendAssignedNames(names);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>An accumulation reads its destination as well as writing it, so everything the
	 * destination and the value read counts.</p>
	 */
	@Override
	public void appendReadPositions(ReadPositions reads) {
		if (reads.visit(value)) value.appendReadPositions(reads);
		if (reads.visit(destination)) destination.appendReadPositions(reads);
	}

	@Override
	public AtomicAccumulation<T> simplify(KernelStructureContext context, int depth) {
		return new AtomicAccumulation<>(
				ScopeSettings.reviewSimplification(destination, destination.simplify(context, depth + 1)),
				ScopeSettings.reviewSimplification(value, value.simplify(context, depth + 1)));
	}

	@Override
	public AtomicAccumulation<T> replace(Expression target, Expression replacement) {
		Expression<T> newDestination = destination.replace(target, replacement);
		Expression<T> newValue = value.replace(target, replacement);

		if (newDestination == destination && newValue == value) return this;
		return new AtomicAccumulation<>(newDestination, newValue);
	}
}
