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
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.ScopeExpressionCollector;
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

	/** Vector size of the rmsnorm layer. Matches {@code LayersTests.SIZE}. */
	private static final int SIZE = 768;

	/**
	 * Builds the rmsnorm forward pipeline, installs a compilation listener,
	 * runs the workload, then scans every captured scope for Expression
	 * duplication. Asserts only that we captured something — the actual numbers
	 * go in the log for the investigation to read.
	 */
	@Test(timeout = 60000)
	public void rmsnormDuplicationProfile() {
		PackedCollection in = new PackedCollection(shape(SIZE));
		in.fill(pos -> Math.random());

		PackedCollection weights = new PackedCollection(shape(SIZE));
		weights.fill(pos -> Math.random());

		SequentialBlock model = new SequentialBlock(shape(SIZE));
		model.add(rmsnorm(weights));

		OperationList op = (OperationList) model.getForward().push(p(in));
		op = op.flatten();
		op = (OperationList) op.optimize();

		List<Scope<?>> capturedScopes = Collections.synchronizedList(new ArrayList<>());
		CompilationTimingListener previous = AbstractComputeContext.compilationTimingListener;
		AbstractComputeContext.compilationTimingListener = new CapturingListener(capturedScopes);

		try {
			op.get().run();
		} finally {
			AbstractComputeContext.compilationTimingListener = previous;
		}

		log("rmsnorm SIZE=" + SIZE + " captured-scopes=" + capturedScopes.size());
		Assert.assertFalse("the compilation listener captured no scopes; "
				+ "is the workload reaching the compute context?",
				capturedScopes.isEmpty());

		List<Expression<?>> roots = new ArrayList<>();
		for (Scope<?> scope : capturedScopes) {
			roots.addAll(ScopeExpressionCollector.collect(scope));
		}

		log("expression-roots=" + roots.size());
		Assert.assertFalse("ScopeExpressionCollector returned no Expression roots",
				roots.isEmpty());

		Report report = ExpressionDuplicationScanner.scan(roots);
		log(report.fullTable());

		Assert.assertTrue("scan found no Expression nodes: " + report.summary(),
				report.getTotalNodes() > 0);
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
