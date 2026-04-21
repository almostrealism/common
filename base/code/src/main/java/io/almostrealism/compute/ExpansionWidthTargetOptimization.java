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

package io.almostrealism.compute;

import org.almostrealism.io.Console;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A {@link ProcessOptimizationStrategy} that isolates children of a parent when
 * the accumulated <em>expansion width</em> along the path from the root to this
 * point in the tree crosses a threshold at sufficient depth.
 *
 * <p>Expansion width generalises the idea previously captured by aggregation
 * count: the factor by which inlining a producer's emitted expression multiplies
 * the consumer's expression size per use-site. Both reductions (matmul, sum of
 * N inputs) and branching producers (concat, greater-than, pad) exhibit this
 * amplification. A producer's expansion width is declared via
 * {@link Process#getExpansionWidth()}; the running value is accumulated by
 * {@link ParallelProcessContext#getExpansionWidth()} during tree traversal.</p>
 *
 * <p>This strategy is the generalised successor of
 * {@link AggregationDepthTargetOptimization}. That strategy was never wired
 * into any live cascade (confirmed 2026-04-20); this one takes its place.</p>
 *
 * <h2>Firing conditions</h2>
 * <ul>
 *   <li>The accumulated expansion width exceeds {@link #EXPANSION_THRESHOLD}.</li>
 *   <li>The context depth exceeds the configured {@code limit} (default 12).</li>
 *   <li>Every child has parallelism &ge; {@link #PARALLELISM_THRESHOLD}, so
 *       isolating them will not create a bottleneck.</li>
 * </ul>
 *
 * <p>When all three conditions hold, {@link #generate(Process, Collection, boolean)}
 * is invoked with {@code isolateChildren=true}. Otherwise the strategy returns
 * {@code null} so the next strategy in the cascade may run.</p>
 *
 * <h2>Threshold tuning</h2>
 * <p>{@link #EXPANSION_THRESHOLD}, {@link #PARALLELISM_THRESHOLD} and the depth
 * limit are all open empirical questions. Initial values are chosen to match
 * {@link AggregationDepthTargetOptimization} so the behaviour is at least no
 * worse than the prior (unused) strategy. See
 * {@code docs/plans/EXPANSION_WIDTH_OPTIMIZATION.md} for the rollout plan and
 * empirical questions.</p>
 *
 * @see Process#getExpansionWidth()
 * @see ParallelProcessContext#getExpansionWidth()
 * @see ProcessOptimizationStrategy
 */
public class ExpansionWidthTargetOptimization implements ProcessOptimizationStrategy {
	/**
	 * Minimum accumulated expansion width required to consider isolation.
	 * Below this, the expression blow-up is not large enough to warrant the
	 * overhead of introducing a kernel boundary.
	 *
	 * <p>The initial value {@code 2} is deliberately low: under product-semantics
	 * propagation (see {@link ParallelProcessContext#enableProductExpansionWidth}),
	 * a width of 4 or higher indicates that at least two conditional or
	 * aggregation producers have stacked along a single path, which is the
	 * regime in which kernel expression blow-up has been observed in practice.
	 * Under the default max-semantics propagation, any single conditional at
	 * sufficient depth will cross this threshold, which is closer to the
	 * previous (never-wired) {@link AggregationDepthTargetOptimization} default
	 * of {@code 64} after accounting for how few producers ever fed a
	 * nontrivial value into the old context field.</p>
	 */
	public static long EXPANSION_THRESHOLD = 32;

	/**
	 * Minimum per-child parallelism required for this strategy to fire.
	 * A child with parallelism below this threshold would become a bottleneck
	 * if run in its own kernel, so isolation is skipped.
	 *
	 * <p>Initial value {@code 16}: intentionally permissive so small-shape
	 * regression tests (including {@code mraRopeRotationAtMoonbeamScale} at
	 * seqLen=16 / 48 output elements) are not filtered out before a chance
	 * to isolate. The previous default of {@code 128} was inherited from
	 * {@link AggregationDepthTargetOptimization} where it was never exercised
	 * in production. Raise as needed if over-isolation of small kernels
	 * becomes a measurable problem.</p>
	 */
	public static long PARALLELISM_THRESHOLD = 16;

	/** The minimum depth at which the strategy will begin to consider isolation. */
	private final int limit;

	/**
	 * Constructs the strategy with the default depth limit of 12.
	 */
	public ExpansionWidthTargetOptimization() { this(12); }

	/**
	 * Constructs the strategy with a custom depth limit.
	 *
	 * @param depthLimit the minimum context depth at which this strategy fires
	 */
	public ExpansionWidthTargetOptimization(int depthLimit) {
		this.limit = depthLimit;
	}

	/**
	 * Diagnostic flag: when true, every {@link #optimize} invocation appends a line
	 * to {@link #diagnosticsFile}. Intended for local inspection during tuning;
	 * leave {@code false} in production.
	 */
	public static boolean enableDiagnostics = false;

	/**
	 * Path of the file to append diagnostic lines to when
	 * {@link #enableDiagnostics} is true. Each write is flushed and closed to
	 * survive JVM kills (which happen when native compilation OOMs).
	 */
	public static String diagnosticsFile = "/tmp/ar-ew-diag.log";

	/**
	 * Appends a single diagnostic line to {@link #diagnosticsFile}, opening and
	 * closing the file on each call to flush writes that would otherwise be lost
	 * when the JVM is killed during native compilation.
	 *
	 * @param line the diagnostic line to append (a newline is added automatically)
	 */
	private static void writeDiagnostic(String line) {
		try (FileWriter fw = new FileWriter(diagnosticsFile, true)) {
			fw.write(line);
			fw.write('\n');
		} catch (IOException ignored) {
			// best-effort diagnostic; ignore write failures
		}
	}

	@Override
	public <P extends Process<?, ?>, T> Process<P, T> optimize(ProcessContext ctx,
															   Process<P, T> parent,
															   Collection<P> children,
															   Function<Collection<P>, Stream<P>> childProcessor) {
		listeners.forEach(l -> l.accept(parent));

		ParallelProcessContext pctx = ParallelProcessContext.of(ctx);

		long belowFloor = childProcessor.apply(children)
				.mapToLong(ParallelProcess::parallelism)
				.filter(p -> p < PARALLELISM_THRESHOLD)
				.count();

		boolean fire = belowFloor == 0
				&& pctx.getExpansionWidth() > EXPANSION_THRESHOLD
				&& pctx.getDepth() > limit;

		if (enableDiagnostics) {
			StringBuilder childSummary = new StringBuilder();
			int shown = 0;
			for (P c : children) {
				if (shown > 0) childSummary.append(',');
				childSummary.append(c == null ? "null" : c.getClass().getSimpleName());
				childSummary.append('(')
						.append(c instanceof ParallelProcess ? "PP" : "P")
						.append(",par=").append(ParallelProcess.parallelism(c))
						.append(",ew=").append(Process.expansionWidth(c))
						.append(')');
				if (++shown >= 8) { childSummary.append(",…"); break; }
			}
			writeDiagnostic("[EW] parent=" + parent.getClass().getSimpleName()
					+ " depth=" + pctx.getDepth()
					+ " ew=" + pctx.getExpansionWidth()
					+ " belowFloor=" + belowFloor
					+ " ownEw=" + Process.expansionWidth(parent)
					+ " fire=" + fire
					+ " children=[" + childSummary + "]");
		}

		if (fire) {
			return generate(parent, children, true);
		}

		return null;
	}
}
