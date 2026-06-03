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
 * <p>A row-monomial matrix has exactly one non-zero entry per row (see
 * {@link Algebraic#isRowMonomial()}); the Jacobian of a subset, slice, or gather is such
 * a matrix. When such a matrix is contracted (summed over its column index) the reduction
 * reduces to reading one element per row — a direct gather — and the reduction machinery
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
 * <h2>Recognition is conservative</h2>
 * <p>{@link Algebraic#isRowMonomial()} defaults to {@code false} and is only overridden by
 * computations that are row-monomial by construction, so a false positive (which would
 * yield a wrong gather, i.e. a silently incorrect gradient) cannot arise from this strategy
 * alone — it acts only on producers that affirmatively declare the property and propagate
 * it through structure-preserving wrappers.</p>
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
