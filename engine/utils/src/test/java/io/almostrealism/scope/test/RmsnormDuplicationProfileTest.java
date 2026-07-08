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

import io.almostrealism.expression.Expression;
import io.almostrealism.profile.CompilationTimingListener;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.ExpressionDuplicationScanner;
import io.almostrealism.scope.ExpressionDuplicationScanner.Report;
import io.almostrealism.scope.LeafInternTable;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.ScopeExpressionCollector;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-workload adaptor that runs the {@code rmsnorm} forward-only pipeline
 * (a {@link SequentialBlock} with a single
 * {@link LayerFeatures#rmsnorm(PackedCollection)} layer, pushed and optimised the
 * same way {@code LayersTests.rmsnorm} does) and reports
 * {@link ExpressionDuplicationScanner} statistics for every {@link Scope} the
 * framework compiles during execution.
 *
 * <p><strong>Snapshot mechanism.</strong> The adaptor does not try to rebuild
 * the compiled scope from outside the pipeline. Instead it installs a
 * {@link CompilationTimingListener} on
 * {@link AbstractComputeContext#compilationTimingListener} that captures every
 * {@link Scope} as it is handed to the compute context for code emission. After
 * the workload finishes, the captured scopes are walked by
 * {@link ScopeExpressionCollector} and the resulting roots are fed to
 * {@link ExpressionDuplicationScanner}. This is the durable entrypoint the
 * profile system already exposes for source-text capture; the listener variant
 * used here is the {@code Scope}-aware default method on
 * {@link CompilationTimingListener#recordCompilation(Scope, String, long)},
 * which the framework's compute contexts call directly.</p>
 *
 * <p>The point of this test is not to validate {@code rmsnorm} itself — that is
 * {@code LayersTests.rmsnorm}'s job. The point is to give the Expression DAG
 * interning investigation a representative, reproducible duplication number for
 * a real forward-pass workload, sampled at exactly the moment the framework
 * actually hands an Expression-bearing scope to the native compiler.</p>
 */
public class RmsnormDuplicationProfileTest extends TestSuiteBase implements LayerFeatures {

	/** Vector size for the baseline run. Matches {@code LayersTests.SIZE}. */
	private static final int SIZE_BASELINE = 768;

	/**
	 * Vector size for the interned run. Distinct from {@link #SIZE_BASELINE} so
	 * the framework's compile cache cannot reuse the first run's compiled
	 * kernels (the shape is part of the signature). The rmsnorm Expression
	 * shape is identical at either size — only concrete dimension constants
	 * differ — so duplication ratios are directly comparable.
	 */
	private static final int SIZE_INTERNED = 752;

	/**
	 * Builds the rmsnorm forward pipeline, runs it twice — once with
	 * {@link ScopeSettings#enableLeafInterning} off (the baseline) and once
	 * with it on, at a slightly different size to force a fresh compile — and
	 * reports {@link ExpressionDuplicationScanner} statistics for both.
	 */
	@Test(timeout = 60000)
	public void rmsnormDuplicationProfile() {
		boolean previousFlag = ScopeSettings.enableLeafInterning;
		LeafInternTable.clear();

		try {
			ScopeSettings.enableLeafInterning = false;
			Report baseline = runOnce("baseline", SIZE_BASELINE);

			LeafInternTable.clear();
			ScopeSettings.enableLeafInterning = true;
			Report interned = runOnce("interned", SIZE_INTERNED);

			log("rmsnorm baseline (SIZE=" + SIZE_BASELINE + "):  " + baseline.summary());
			log("rmsnorm interned (SIZE=" + SIZE_INTERNED + "):  " + interned.summary());
			log("LeafInternTable size after interned run = " + LeafInternTable.size());
		} finally {
			ScopeSettings.enableLeafInterning = previousFlag;
			LeafInternTable.clear();
		}
	}

	/**
	 * Runs the rmsnorm pipeline once with the current
	 * {@link ScopeSettings#enableLeafInterning} setting, captures all
	 * compiled scopes via the static {@link CompilationTimingListener} hook,
	 * scans them, and returns the report. Logs the per-class table under the
	 * given label so baseline and interned runs are easy to distinguish in
	 * the test output.
	 *
	 * @param label tag printed before this run's report
	 * @param size  rmsnorm vector size (controls the compile signature so two
	 *              calls at different sizes both produce a fresh compile)
	 * @return the duplication report for this run
	 */
	private Report runOnce(String label, int size) {
		PackedCollection in = new PackedCollection(shape(size));
		in.fill(pos -> Math.random());

		PackedCollection weights = new PackedCollection(shape(size));
		weights.fill(pos -> Math.random());

		SequentialBlock model = new SequentialBlock(shape(size));
		model.add(rmsnorm(weights));

		OperationList op = (OperationList) model.getForward().push(p(in));
		op = op.flatten();
		op = (OperationList) op.optimize();

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
			op.get().run();
		} finally {
			ScopeSettings.enableInstructionSetReuse = previousReuse;
			AbstractComputeContext.compilationTimingListener = previous;
		}

		log("rmsnorm [" + label + "] SIZE=" + size + " captured-scopes=" + capturedScopes.size());
		Assert.assertFalse("the compilation listener captured no scopes for run " + label,
				capturedScopes.isEmpty());

		List<Expression<?>> roots = new ArrayList<>();
		for (Scope<?> scope : capturedScopes) {
			roots.addAll(ScopeExpressionCollector.collect(scope));
		}

		log("rmsnorm [" + label + "] expression-roots=" + roots.size());
		Assert.assertFalse("ScopeExpressionCollector returned no Expression roots for run " + label,
				roots.isEmpty());

		Report report = ExpressionDuplicationScanner.scan(roots);
		log("rmsnorm [" + label + "]\n" + report.fullTable());

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
