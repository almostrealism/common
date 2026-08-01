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

package io.almostrealism.scope.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.expression.Expression;
import io.almostrealism.profile.CompilationTimingListener;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.ExpressionDuplicationScanner;
import io.almostrealism.scope.ExpressionDuplicationScanner.Report;
import io.almostrealism.scope.LeafInternTable;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.ScopeExpressionCollector;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-workload adaptor that runs the scaled-dot-product attention forward
 * computation (the same shape as {@code AttentionTests.attentionKeys}) and
 * reports {@link ExpressionDuplicationScanner} statistics for every
 * {@link Scope} the framework hands its compute context during compilation.
 *
 * <p>Attention is the workload the investigation predicted has the strongest
 * a-priori reason for cross-kernel sharing: the three Q/K/V projections that
 * read from a common input tensor would, in a real model, produce three
 * separate kernels whose Expression trees all reference the same source. The
 * {@code attentionKeys} pipeline here exercises only the
 * {@code (Q . K^T) / sqrt(d)} half of that — the part that survives without
 * extra dependencies — but it is still the workload with the most independent
 * passes over shared data in the pinned reference set.</p>
 *
 * <p>Snapshot mechanism is the same listener-based capture used by
 * {@link RmsnormDuplicationProfileTest}; see that class for the rationale.</p>
 */
public class AttentionKeysDuplicationProfileTest extends TestSuiteBase implements
		ExpressionFeatures, CollectionFeatures {

	/** Sequence length for the baseline run; matches {@code AttentionTests.attentionKeys}. */
	private static final int SEQ_LENGTH_BASELINE = 128;

	/**
	 * Sequence length for the interned run. Distinct from
	 * {@link #SEQ_LENGTH_BASELINE} so the framework's compile cache cannot
	 * reuse the first run's kernels — only the concrete dimension constants
	 * differ between the two runs.
	 */
	private static final int SEQ_LENGTH_INTERNED = 112;

	/** Attention heads; matches {@code AttentionTests.attentionKeys}. */
	private static final int HEADS = 12;
	/** Head size; matches {@code AttentionTests.attentionKeys}. */
	private static final int HEAD_SIZE = 64;

	/**
	 * Builds the attention-keys forward pipeline, runs it twice — once with
	 * {@link ScopeSettings#enableLeafInterning} off (the baseline) and once
	 * with it on — and reports {@link ExpressionDuplicationScanner} statistics
	 * for both. Same shape as
	 * {@code RmsnormDuplicationProfileTest.rmsnormDuplicationProfile}; see
	 * that test for the rationale on the same-JVM A/B approach.
	 */
	@Test(timeout = 120000)
	public void attentionKeysDuplicationProfile() {
		boolean previousFlag = ScopeSettings.enableLeafInterning;
		LeafInternTable.clear();

		try {
			ScopeSettings.enableLeafInterning = false;
			Report baseline = runOnce("baseline", SEQ_LENGTH_BASELINE);

			LeafInternTable.clear();
			ScopeSettings.enableLeafInterning = true;
			Report interned = runOnce("interned", SEQ_LENGTH_INTERNED);

			log("attentionKeys baseline (seq=" + SEQ_LENGTH_BASELINE + "):  " + baseline.summary());
			log("attentionKeys interned (seq=" + SEQ_LENGTH_INTERNED + "):  " + interned.summary());
			log("LeafInternTable size after interned run = " + LeafInternTable.size());
		} finally {
			ScopeSettings.enableLeafInterning = previousFlag;
			LeafInternTable.clear();
		}
	}

	/**
	 * Runs the attention-keys pipeline once with the current
	 * {@link ScopeSettings#enableLeafInterning} setting, captures all compiled
	 * scopes, scans them, and returns the report.
	 *
	 * @param label     tag printed before this run's report
	 * @param seqLength sequence length (drives the compile signature so two
	 *                  calls at different lengths both produce a fresh compile)
	 * @return the duplication report for this run
	 */
	private Report runOnce(String label, int seqLength) {
		TraversalPolicy inputShape = shape(HEADS, HEAD_SIZE);
		TraversalPolicy keyShape = shape(seqLength, HEADS, HEAD_SIZE);
		TraversalPolicy outputShape = shape(HEADS, seqLength);

		PackedCollection q = new PackedCollection(inputShape);
		PackedCollection keyCache = new PackedCollection(keyShape);

		q.fill(pos -> Math.random());
		keyCache.fill(pos -> Math.random());

		Producer<PackedCollection> o = c(p(keyCache))
				.traverse(1).multiply(p(q))
				.traverse(2).sum()
				.divide(c(Math.sqrt(HEAD_SIZE)))
				.reshape(shape(seqLength, HEADS))
				.enumerate(1, 1)
				.reshape(outputShape);

		List<Scope<?>> capturedScopes = Collections.synchronizedList(new ArrayList<>());
		CompilationTimingListener previous = AbstractComputeContext.compilationTimingListener;
		AbstractComputeContext.compilationTimingListener = new CapturingListener(capturedScopes);

		// Compile the scope fresh rather than reusing a cached instruction set. The JVM-wide
		// signature cache (DefaultComputer.instructionsCache) would otherwise serve a
		// structurally identical scope compiled by an earlier test in the same JVM, so no
		// compilation would occur here and the listener would observe nothing.
		boolean previousReuse = ScopeSettings.enableInstructionSetReuse;
		ScopeSettings.enableInstructionSetReuse = false;

		try {
			((Evaluable<PackedCollection>) ((ParallelProcess) o).optimize().get()).evaluate();
		} finally {
			ScopeSettings.enableInstructionSetReuse = previousReuse;
			AbstractComputeContext.compilationTimingListener = previous;
		}

		log("attentionKeys [" + label + "] seq=" + seqLength + " heads=" + HEADS
				+ " head=" + HEAD_SIZE + " captured-scopes=" + capturedScopes.size());
		Assert.assertFalse("the compilation listener captured no scopes for run " + label,
				capturedScopes.isEmpty());

		List<Expression<?>> roots = new ArrayList<>();
		for (Scope<?> scope : capturedScopes) {
			roots.addAll(ScopeExpressionCollector.collect(scope));
		}

		log("attentionKeys [" + label + "] expression-roots=" + roots.size());
		Assert.assertFalse("ScopeExpressionCollector returned no Expression roots for run " + label,
				roots.isEmpty());

		Report report = ExpressionDuplicationScanner.scan(roots);
		log("attentionKeys [" + label + "]\n" + report.fullTable());

		Assert.assertTrue("scan found no Expression nodes for run " + label + ": "
				+ report.summary(), report.getTotalNodes() > 0);

		return report;
	}

	/**
	 * Records every {@link Scope} that the framework hands to its compute context
	 * during compilation. The source-code parameter is ignored — only the
	 * structural scope matters for the duplication investigation.
	 */
	private static final class CapturingListener implements CompilationTimingListener {
		/** The list to append captured scopes into. Synchronised by the caller. */
		private final List<Scope<?>> capturedScopes;

		/**
		 * Creates a capturing listener that appends into the given list.
		 *
		 * @param capturedScopes the list to append into
		 */
		CapturingListener(List<Scope<?>> capturedScopes) {
			this.capturedScopes = capturedScopes;
		}

		@Override
		public void recordCompilation(Scope<?> scope, String source, long nanos) {
			if (scope != null) capturedScopes.add(scope);
		}

		@Override
		public void recordCompilation(OperationMetadata metadata,
				List<ArrayVariable<?>> arguments, String code, long nanos) {
			// Unused; the framework calls the Scope-aware overload above.
		}
	}
}
