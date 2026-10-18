/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.compute;

import io.almostrealism.collect.Algebraic;

import java.util.Collection;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Optimization strategy that keeps a <em>row-monomial</em> child visible (un-isolated)
 * so a downstream contraction can collapse it into a gather.
 *
 * <p>A row-monomial matrix has at most one non-zero entry per row (see
 * {@link Algebraic#isRowMonomial()}; a row may also be entirely zero); the Jacobian of a
 * subset, slice, or gather is such a matrix. When such a matrix is contracted (summed over
 * its column index) the reduction reduces to reading at most one element per row — a direct
 * gather — and the reduction machinery
 * in the aggregation computations already performs that collapse when it can <em>see</em>
 * the row-monomial structure of its input. The general-purpose
 * {@link ParallelismTargetOptimization}, judging only by magnitude, isolates the
 * row-monomial computation into its own kernel; that isolation hides the structure and
 * forces the dense {@code count}-term reduction loop instead of the gather.</p>
 *
 * <p>This strategy intercepts that decision. When any child is recognised as row-monomial
 * it claims the optimization and rebuilds the parent with its children left inline (no
 * isolation), so the structure remains visible for the collapse. When no child is
 * row-monomial it returns {@code null}, deferring to the next strategy in the
 * {@link CascadingOptimizationStrategy} chain. It is therefore designed to run
 * <strong>before</strong> {@link ParallelismTargetOptimization}.</p>
 *
 * <h2>First-cut scope</h2>
 * <p>When it fires, this strategy currently leaves <em>all</em> of the parent's children
 * inline, not only the row-monomial one. This is the conservative choice for the parent of
 * a row-monomial contraction, whose other children are the (small) operands of the same
 * contraction; it deliberately does not attempt to reproduce
 * {@link ParallelismTargetOptimization}'s per-child isolation ladder for the siblings.
 * Parents that merely happen to contain a row-monomial child among otherwise
 * isolation-worthy siblings are a possible future refinement (isolate the siblings, keep
 * only the row-monomial child inline); for now correctness and the bounded blast radius
 * are preferred over that generality.</p>
 *
 * <h2>Recognition is conservative, and a false positive is still sound</h2>
 * <p>{@link Algebraic#isRowMonomial()} defaults to {@code false} and is only overridden by
 * computations that are row-monomial by construction or that carry the property through from
 * an operand (an element-wise product with a row-monomial factor, or a sum whose single
 * non-zero operand is row-monomial), so this strategy acts only on
 * producers that affirmatively declare the property. Even so, that declaration is not
 * trusted to be exact: a wrapper such as a {@code reshape} delegates the flag through
 * unchanged, so a reshape that merges rows can leave a producer reporting {@code true} whose
 * merged row now holds more than one non-zero entry. This does not yield a wrong gradient,
 * because this strategy never substitutes a value for the flag — it only keeps the child
 * <em>inline</em> so the downstream reduction machinery can attempt the collapse. That
 * collapse is an independent, value-based analysis (a unique-non-zero-offset scan over the
 * actual expression) that reads the true value at each row and declines to collapse any row
 * whose non-zero entry is not unique, falling back to the dense reduction. The flag
 * therefore only gates <em>whether the collapse is attempted</em>, never <em>what value it
 * reads</em>, so a false positive costs at most a missed optimization, never correctness.</p>
 *
 * @see Algebraic#isRowMonomial()
 * @see CascadingOptimizationStrategy
 * @see ParallelismTargetOptimization
 * @see ProcessOptimizationStrategy
 *
 * @author Michael Murray
 */
public class RowMonomialOptimization implements ProcessOptimizationStrategy {

	/**
	 * When {@code false}, this strategy always cascades ({@code optimize} returns
	 * {@code null}), restoring the previous behaviour without removing it from the chain.
	 * Provided so the row-monomial collapse can be toggled for A/B comparison and as a
	 * safety switch.
	 */
	public static boolean enabled = true;

	/**
	 * Keeps row-monomial children inline so a downstream contraction can collapse them
	 * to a gather.
	 *
	 * <p>If any child is recognised as row-monomial (via {@link Algebraic#isRowMonomial(Object)})
	 * this returns the parent regenerated with all children inline; otherwise it returns
	 * {@code null} to defer to the next strategy in the chain.</p>
	 *
	 * @param <P>            the type of child processes
	 * @param <T>            the result type of the process
	 * @param ctx            the process context
	 * @param parent         the parent process being optimized
	 * @param children       the collection of child processes
	 * @param childProcessor a function to process children for analysis
	 * @return the parent regenerated with children inline when a row-monomial child is
	 *         present, or {@code null} to cascade
	 */
	@Override
	public <P extends Process<?, ?>, T> Process<P, T> optimize(
			ProcessContext ctx,
			Process<P, T> parent,
			Collection<P> children,
			Function<Collection<P>, Stream<P>> childProcessor) {
		listeners.forEach(l -> l.accept(parent));

		if (!enabled) return null;

		boolean anyRowMonomial = children.stream().anyMatch(Algebraic::isRowMonomial);
		if (!anyRowMonomial) return null;

		return generate(parent, children, false);
	}
}
