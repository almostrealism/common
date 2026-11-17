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

import io.almostrealism.code.ComputationBase;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.scope.Cases;
import io.almostrealism.scope.ExplicitScope;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests that {@link Scope#generate(List)} preserves a scope's configuration — its
 * reference index, {@link Scope#getComputeRequirements() compute requirements} and
 * {@link Scope#getLaneCount() lane count} — for every scope type, not just a plain
 * {@link Scope}.
 *
 * <p>{@link Scope#simplify(io.almostrealism.kernel.KernelStructureContext, int)} rebuilds its
 * result through {@code generate}, and a kernel's root scope is commonly a {@link Repeated},
 * {@link Cases} or {@link HybridScope}. A kernel whose root scope dropped its compute
 * requirements during simplification would be dispatched with no requirements, so the
 * requirements carried by a {@link Repeated}, {@link Cases} or {@link HybridScope} must survive
 * simplification exactly as a plain {@link Scope}'s do. The reference index must likewise
 * survive, so that the names given to extracted subexpressions remain unique across
 * simplification.</p>
 *
 * @see Scope#generate(List)
 * @see Scope#createGenerated()
 */
public class ScopeGenerateConfigurationTest extends TestSuiteBase {

	/**
	 * A reduction over a traversed collection compiles to a kernel whose root scope originates
	 * from a {@link Repeated} loop and is rebuilt through {@link Scope#generate(List)} during
	 * {@link Scope#simplify(io.almostrealism.kernel.KernelStructureContext, int)}, then wrapped
	 * in a plain {@link Scope} when its accumulator is promoted. This exercises that whole chain
	 * end-to-end: the reduction must compile and evaluate to the correct per-row sums, which it
	 * cannot do if {@code generate} reproduces an invalid {@link Repeated} (one without a
	 * condition or interval) in place of the plain wrapper the promotion produces. Compute
	 * requirements are set on the reduction to confirm they do not disturb that compilation.
	 */
	@Test(timeout = 120000)
	public void reductionCompilesAndEvaluatesThroughGenerate() {
		int rows = 4;
		int cols = 8;

		try (PackedCollection data = new PackedCollection(shape(rows, cols)).randFill()) {
			CollectionProducer sum = cp(data).traverse(1).sum();
			((ComputationBase) sum).setComputeRequirements(List.of(ComputeRequirement.CPU));

			PackedCollection result = (PackedCollection) sum.get().evaluate();

			for (int row = 0; row < rows; row++) {
				double expected = 0.0;
				for (int col = 0; col < cols; col++) {
					expected += data.toDouble(row * cols + col);
				}

				Assert.assertEquals("Row " + row + " sum", expected, result.toDouble(row), 1e-5);
			}
		}
	}

	/**
	 * A {@link Cases} scope keeps its compute requirements and lane count through
	 * simplification, as the superclass path depends on {@link Scope#generate(List)} rebuilding
	 * the scope with its configuration intact.
	 */
	@Test(timeout = 30000)
	public void casesScopeKeepsConfigurationThroughSimplify() {
		OperationMetadata metadata = new OperationMetadata("casesConfig", "Configured cases");
		Cases<Void> cases = new Cases<>("casesConfig", metadata);
		cases.setComputeRequirements(List.of(ComputeRequirement.CPU));
		cases.setLaneCount(2);
		cases.addCase(new BooleanConstant(true), new Scope<>("branch"), null);

		Scope<Void> simplified = cases.simplify(new NoOpKernelStructureContext());

		Assert.assertTrue("A simplified Cases should remain a Cases", simplified instanceof Cases);
		Assert.assertEquals(List.of(ComputeRequirement.CPU), simplified.getComputeRequirements());
		Assert.assertEquals(2, simplified.getLaneCount());
	}

	/**
	 * A {@link Cases} created without metadata — as {@link Scope#addCase} does for a runtime
	 * conditional — must still simplify without error. {@link Scope#generate(List)} rebuilds it
	 * through {@link Scope#createGenerated()}, which must tolerate the absent metadata rather than
	 * demand the metadata-bearing constructor.
	 */
	@Test(timeout = 30000)
	public void metadatalessCasesSimplifiesWithoutError() {
		Cases<Void> cases = new Cases<>();
		cases.addCase(new BooleanConstant(true), new Scope<>("branch"), null);

		Scope<Void> simplified = cases.simplify(new NoOpKernelStructureContext());

		Assert.assertTrue("A Cases without metadata should still simplify", simplified instanceof Cases);
	}

	/**
	 * A {@link HybridScope} keeps its compute requirements and lane count through
	 * simplification, the same contract verified for {@link Cases} in
	 * {@link #casesScopeKeepsConfigurationThroughSimplify()}.
	 */
	@Test(timeout = 30000)
	public void hybridScopeKeepsConfigurationThroughSimplify() {
		OperationMetadata metadata = new OperationMetadata("hybridConfig", "Configured hybrid");
		ExplicitScope<Void> explicit = new ExplicitScope<>("hybridBody", metadata, "external();");
		HybridScope<Void> hybrid = new HybridScope<>(explicit, null);
		hybrid.setName("hybridConfig");
		hybrid.setMetadata(metadata);
		hybrid.setComputeRequirements(List.of(ComputeRequirement.GPU));
		hybrid.setLaneCount(3);

		Scope<Void> simplified = hybrid.simplify(new NoOpKernelStructureContext());

		Assert.assertTrue("A simplified HybridScope should remain a HybridScope",
				simplified instanceof HybridScope);
		Assert.assertEquals(List.of(ComputeRequirement.GPU), simplified.getComputeRequirements());
		Assert.assertEquals(3, simplified.getLaneCount());
	}

	/**
	 * {@link Scope#generate(List)} copies the reference index for every scope type, so that the
	 * unique names it seeds for extracted subexpressions continue from where the original left
	 * off rather than restarting. The reference index is private with no accessor, so it is read
	 * reflectively.
	 */
	@Test(timeout = 30000)
	public void generatePreservesReferenceIndex() throws ReflectiveOperationException {
		OperationMetadata metadata = new OperationMetadata("refIdx", "Reference index");
		ExplicitScope<Void> explicit = new ExplicitScope<>("refIdxHybridBody", metadata, "external();");
		HybridScope<Void> hybrid = new HybridScope<>(explicit, null);
		hybrid.setName("refIdxHybrid");
		hybrid.setMetadata(metadata);

		List<Scope<Void>> sources = List.of(
				new Scope<>("refIdxScope", metadata),
				new Repeated<>("refIdxRepeated", metadata),
				new Cases<>("refIdxCases", metadata),
				hybrid);

		Field field = Scope.class.getDeclaredField("refIdx");
		field.setAccessible(true);

		for (Scope<Void> source : sources) {
			field.setInt(source, 7);
			Scope<?> generated = (Scope<?>) source.generate(new ArrayList<>());
			Assert.assertEquals(source.getClass().getSimpleName() + " should copy the reference index",
					7, field.getInt(generated));
		}
	}
}
