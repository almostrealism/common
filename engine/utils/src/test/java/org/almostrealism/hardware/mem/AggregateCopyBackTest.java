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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.Statement;
import io.almostrealism.code.Termination;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Cases;
import io.almostrealism.scope.ExplicitScope;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

/**
 * Tests that the copy-back after a kernel with aggregated arguments is limited to the slices
 * of the aggregate that the kernel's own {@link Scope} may write, and that nothing beyond the
 * scope is consulted to decide it: the scope is read, and a scope whose writes cannot be
 * enumerated copies every slice back.
 */
public class AggregateCopyBackTest extends TestSuiteBase {

	/**
	 * A scope that assigns one aggregated argument from another writes only the first one's
	 * slice, so only that slice is copied back: the root the scope only read keeps its own
	 * contents even though its staged slice of the aggregate was changed.
	 */
	@Test(timeout = 60000)
	public void copyBackIsLimitedToTheSlicesTheScopeWrites() {
		List<PackedCollection> aggregates = new ArrayList<>();

		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), length -> {
						PackedCollection aggregate = new PackedCollection(length);
						aggregates.add(aggregate);
						return aggregate;
					});

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));
				Assert.assertEquals(4, map.getAggregateLength());

				Scope<?> kernel = new Scope<>("copyBack");
				for (int i = 0; i < 2; i++) {
					kernel.assign(writtenArg.reference(new IntegerConstant(i)),
							readArg.reference(new IntegerConstant(i)));
				}

				BitSet slices = map.limitCopyBackTo(kernel);
				Assert.assertFalse(slices.get(0));
				Assert.assertTrue(slices.get(1));
				Assert.assertEquals(1, map.getPostprocessOperations(null).size());

				Semaphore copiedIn = Submittable.submit(map.getPrepareOperations(), null);
				if (copiedIn != null) copiedIn.waitFor();
				Assert.assertEquals(1, aggregates.size());

				aggregates.get(0).fill(10.0, 20.0, 30.0, 40.0);
				Semaphore copiedOut = Submittable.submit(map.getPostprocessOperations(null), null);
				if (copiedOut != null) copiedOut.waitFor();

				Assert.assertArrayEquals(new double[] { 1.0, 2.0 }, read.toArray(), 0.0);
				Assert.assertArrayEquals(new double[] { 30.0, 40.0 }, written.toArray(), 0.0);
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A write whose position has no upper bound (a kernel index with no kernel size) may reach
	 * any slice from its lower bound on, so the slice before it is left alone while every slice
	 * from the written one on is copied back.
	 */
	@Test(timeout = 60000)
	public void unboundedWriteReachesEverySliceFromItsLowerBound() {
		try (PackedCollection first = pack(1.0, 2.0);
			 PackedCollection second = pack(3.0, 4.0);
			 PackedCollection third = pack(5.0, 6.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				map.get(p(first));
				ArrayVariable<?> secondArg = map.get(p(second));
				map.get(p(third));

				Scope<?> kernel = new Scope<>("unbounded");
				kernel.assign(secondArg.reference(new KernelIndex()), new IntegerConstant(0));

				BitSet slices = map.getWrittenSlices(kernel);
				Assert.assertFalse(slices.get(0));
				Assert.assertTrue(slices.get(1));
				Assert.assertTrue(slices.get(2));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A write to the first element of a slice is not attributed to the slice that ends right
	 * before it, and a write to the last element of a slice is not attributed to the slice that
	 * starts right after it: each slice is the half-open range of its own positions.
	 */
	@Test(timeout = 60000)
	public void writeAtSliceEdgeReachesOnlyItsOwnSlice() {
		try (PackedCollection first = pack(1.0, 2.0);
			 PackedCollection second = pack(3.0, 4.0);
			 PackedCollection third = pack(5.0, 6.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				map.get(p(first));
				ArrayVariable<?> secondArg = map.get(p(second));
				map.get(p(third));

				Scope<?> firstElement = new Scope<>("firstElement");
				firstElement.assign(secondArg.reference(new IntegerConstant(0)), new IntegerConstant(0));
				BitSet slices = map.getWrittenSlices(firstElement);
				Assert.assertFalse(slices.get(0));
				Assert.assertTrue(slices.get(1));
				Assert.assertFalse(slices.get(2));

				Scope<?> lastElement = new Scope<>("lastElement");
				lastElement.assign(secondArg.reference(new IntegerConstant(1)), new IntegerConstant(0));
				slices = map.getWrittenSlices(lastElement);
				Assert.assertFalse(slices.get(0));
				Assert.assertTrue(slices.get(1));
				Assert.assertFalse(slices.get(2));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A scope whose writes cannot be enumerated from its assignments (here, one that calls a
	 * method) may write anything, so every slice is reported as written.
	 */
	@Test(timeout = 60000)
	public void scopeWithOpaqueStatementsWritesEverySlice() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));

				Scope<?> kernel = new Scope<>("opaque");
				kernel.assign(writtenArg.reference(new IntegerConstant(0)),
						readArg.reference(new IntegerConstant(0)));
				kernel.getMethods().add(new Method<>("external", new IntegerConstant(0)));

				BitSet slices = map.getWrittenSlices(kernel);
				Assert.assertTrue(slices.get(0));
				Assert.assertTrue(slices.get(1));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A required scope is generated as a separate function whose writes are not among the
	 * assignments of the scope tree, so a kernel with a required scope anywhere in its tree,
	 * including on a child whose own statements are plain assignments, may write anything and
	 * every slice is reported as written.
	 */
	@Test(timeout = 60000)
	public void childWithRequiredScopeWritesEverySlice() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));

				Scope<Void> kernel = new Scope<>("parent");
				Scope<Void> child = new Scope<>("child");
				child.assign(writtenArg.reference(new IntegerConstant(0)),
						readArg.reference(new IntegerConstant(0)));
				kernel.getChildren().add(child);

				BitSet narrowed = map.getWrittenSlices(kernel);
				Assert.assertFalse(narrowed.get(0));
				Assert.assertTrue(narrowed.get(1));

				Scope<?> required = new Scope<>("required");
				required.assign(readArg.reference(new IntegerConstant(1)), new IntegerConstant(0));
				child.getRequiredScopes().add(required);

				BitSet slices = map.getWrittenSlices(kernel);
				Assert.assertTrue(slices.get(0));
				Assert.assertTrue(slices.get(1));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A loop is analyzed like any other scope: its condition and interval are expressions, which
	 * read memory but never write it, so a loop whose body assigns, at the position of the kernel
	 * thread, one aggregated argument from the element of another at the loop index, under a
	 * condition that also reads the other, writes only the first one's slice.
	 */
	@Test(timeout = 60000)
	public void loopWritesOnlyTheSlicesItsBodyAssigns() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));

				DefaultIndex index = new DefaultIndex("i", 2);
				Repeated<Void> loop = new Repeated<>(Variable.integer("i"),
						index.lessThan(readArg.reference(new IntegerConstant(1))));
				Scope<Void> body = new Scope<>("body");
				body.assign(writtenArg.reference(new KernelIndex()), readArg.reference(index));
				loop.getChildren().add(body);

				BitSet slices = map.getWrittenSlices(loop);
				Assert.assertFalse("The slice the loop only reads is not written", slices.get(0));
				Assert.assertTrue(slices.get(1));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A choice among branches is analyzed like any other scope: its conditions are expressions,
	 * which read memory but never write it, so a branch that assigns one aggregated argument,
	 * taken on a condition that reads another, writes only the first one's slice.
	 */
	@Test(timeout = 60000)
	public void branchesWriteOnlyTheSlicesTheyAssign() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));

				Cases<Void> cases = new Cases<>("branches");
				Scope<Void> branch = new Scope<>("branch");
				branch.assign(writtenArg.reference(new IntegerConstant(1)), new IntegerConstant(0));
				cases.addCase(readArg.reference(new IntegerConstant(0)).greaterThan(new IntegerConstant(0)),
						branch, null);

				BitSet slices = map.getWrittenSlices(cases);
				Assert.assertFalse("The slice only the condition reads is not written", slices.get(0));
				Assert.assertTrue(slices.get(1));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A loop reads what its body reads and what its condition reads, and nothing else: a loop
	 * whose body assigns one aggregated argument from a second, under a condition that reads a
	 * third, reads the slices of the second and the third but not that of the first.
	 */
	@Test(timeout = 60000)
	public void loopReadsOnlyWhatItsBodyAndConditionRead() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection bound = pack(2.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> boundArg = map.get(p(bound));
				ArrayVariable<?> writtenArg = map.get(p(written));

				DefaultIndex index = new DefaultIndex("i", 2);
				Repeated<Void> loop = new Repeated<>(Variable.integer("i"),
						index.lessThan(boundArg.reference(new IntegerConstant(0))));
				Scope<Void> body = new Scope<>("body");
				body.assign(writtenArg.reference(new KernelIndex()), readArg.reference(index));
				loop.getChildren().add(body);

				BitSet slices = map.getReadSlices(loop);
				Assert.assertTrue("The body reads the first slice", slices.get(0));
				Assert.assertTrue("The condition reads the second slice", slices.get(1));
				Assert.assertFalse("The slice the loop only writes is not read", slices.get(2));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A choice among branches reads what its conditions read and what its branches read, and
	 * nothing else: a branch that assigns a constant to one aggregated argument, taken on a
	 * condition that reads a second, reads only the second one's slice.
	 */
	@Test(timeout = 60000)
	public void branchesReadOnlyWhatTheirConditionsAndBranchesRead() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection untouched = pack(5.0, 6.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				map.get(p(untouched));
				ArrayVariable<?> writtenArg = map.get(p(written));

				Cases<Void> cases = new Cases<>("branches");
				Scope<Void> branch = new Scope<>("branch");
				branch.assign(writtenArg.reference(new IntegerConstant(1)), new IntegerConstant(0));
				cases.addCase(readArg.reference(new IntegerConstant(0)).greaterThan(new IntegerConstant(0)),
						branch, null);

				BitSet slices = map.getReadSlices(cases);
				Assert.assertTrue("The condition reads the first slice", slices.get(0));
				Assert.assertFalse("No code reads the second slice", slices.get(1));
				Assert.assertFalse("The slice the branch only writes is not read", slices.get(2));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * Explicit code is not made of assignments that can be enumerated, so a kernel with a child
	 * of explicit code, whether on its own or combined with a generated body, may write anything,
	 * and every slice is reported as written.
	 */
	@Test(timeout = 60000)
	public void explicitCodeWritesEverySlice() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));
				ExplicitScope<Void> explicit = new ExplicitScope<>("explicit",
						new OperationMetadata("explicit", "Explicit code"), "external();");

				Scope<Void> kernel = new Scope<>("parent");
				kernel.assign(writtenArg.reference(new IntegerConstant(0)),
						readArg.reference(new IntegerConstant(0)));
				Assert.assertFalse(map.getWrittenSlices(kernel).get(0));

				kernel.getChildren().add(explicit);
				BitSet slices = map.getWrittenSlices(kernel);
				Assert.assertTrue(slices.get(0));
				Assert.assertTrue(slices.get(1));

				Scope<Void> hybrid = new Scope<>("hybridParent");
				hybrid.getChildren().add(new HybridScope<>(explicit, null));
				slices = map.getWrittenSlices(hybrid);
				Assert.assertTrue(slices.get(0));
				Assert.assertTrue(slices.get(1));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * Every statement answers for what it writes, and an early exit writes nothing, so a kernel
	 * that returns when one aggregated argument is positive and otherwise assigns another writes
	 * only the second one's slice.
	 */
	@Test(timeout = 60000)
	public void earlyExitWritesNoSlice() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));

				Scope<Void> kernel = new Scope<>("earlyExit");
				kernel.addCase(readArg.reference(new IntegerConstant(0)).greaterThan(new IntegerConstant(0)),
						new Termination());
				Scope<Void> body = new Scope<>("body");
				body.assign(writtenArg.reference(new IntegerConstant(1)), new IntegerConstant(0));
				kernel.getChildren().add(body);

				BitSet slices = map.getWrittenSlices(kernel);
				Assert.assertFalse("The slice only the exit condition reads is not written", slices.get(0));
				Assert.assertTrue(slices.get(1));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * Declaring a local array writes no element of any argument, so a kernel that stages one
	 * aggregated argument in a local array and assigns another from it writes only the second
	 * one's slice.
	 */
	@Test(timeout = 60000)
	public void localArrayDeclarationWritesNoSlice() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));

				Scope<Void> kernel = new Scope<>("localArray");
				ArrayVariable<?> staged = kernel.declareArray("staged", new IntegerConstant(2));
				kernel.assign(staged.reference(new IntegerConstant(0)), readArg.reference(new IntegerConstant(0)));
				kernel.assign(writtenArg.reference(new IntegerConstant(1)), staged.reference(new IntegerConstant(0)));

				BitSet slices = map.getWrittenSlices(kernel);
				Assert.assertFalse("The slice staged in the local array is not written", slices.get(0));
				Assert.assertTrue(slices.get(1));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A call runs a function whose body is not among the statements of the kernel, so a kernel
	 * whose statements include one may write anything, and every slice is reported as written,
	 * just as for a call among its methods.
	 */
	@Test(timeout = 60000)
	public void callStatementWritesEverySlice() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));

				Scope<Void> kernel = new Scope<>("call");
				kernel.assign(writtenArg.reference(new IntegerConstant(0)),
						readArg.reference(new IntegerConstant(0)));
				Assert.assertFalse(map.getWrittenSlices(kernel).get(0));

				kernel.getStatements().add(new Method<>("external", new IntegerConstant(0)));
				BitSet slices = map.getWrittenSlices(kernel);
				Assert.assertTrue(slices.get(0));
				Assert.assertTrue(slices.get(1));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A statement that gives no account of its own effects is answered for by the conservative
	 * defaults of {@link Statement}: it may write, and may read, any position of any array, so a
	 * kernel whose statements include one reports every slice as written.
	 */
	@Test(timeout = 60000)
	public void statementWithoutAccountWritesAndReadsEverything() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));
				Statement<?> unknown = new UnaccountedStatement();

				Assert.assertTrue(unknown.containsAssignment(readArg, 0, 1));
				Assert.assertTrue(unknown.getReadPositions(readArg).isEverything());
				Assert.assertTrue(unknown.containsRead(writtenArg, 1, 2));

				Scope<Void> kernel = new Scope<>("unaccounted");
				kernel.assign(writtenArg.reference(new IntegerConstant(0)),
						readArg.reference(new IntegerConstant(0)));
				Assert.assertFalse(map.getWrittenSlices(kernel).get(0));

				kernel.getStatements().add(unknown);
				BitSet slices = map.getWrittenSlices(kernel);
				Assert.assertTrue(slices.get(0));
				Assert.assertTrue(slices.get(1));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * The copy-back is built once, so the written slices must be known before it is: limiting
	 * it afterwards would leave the already built copy-back in force, and is refused.
	 */
	@Test(timeout = 60000)
	public void writtenSlicesCannotBeGivenAfterTheCopyBackIsBuilt() {
		try (PackedCollection read = pack(1.0, 2.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				map.get(p(read));
				Assert.assertEquals(1, map.getPostprocessOperations(null).size());
				try {
					map.setWrittenSlices(new BitSet());
					Assert.fail("Written slices given after the copy-back was built were accepted");
				} catch (IllegalStateException expected) {
					Assert.assertEquals(1, map.getPostprocessOperations(null).size());
				}
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * An in-place assignment over small (aggregated) arguments, run as an operation with no
	 * explicit output, still carries its result back into the destination, and leaves the
	 * argument it only read unchanged.
	 */
	@Test(timeout = 60000)
	public void inPlaceAssignmentOfAggregatedArgumentsWritesItsDestination() {
		try (PackedCollection x = pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);
			 PackedCollection y = pack(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0)) {
			OperationList op = new OperationList("in place add");
			op.add(new Assignment<>(1, p(x.each()), cp(x.each()).add(cp(y.each()))));
			op.get().run();

			Assert.assertArrayEquals(new double[] { 11.0, 22.0, 33.0, 44.0, 55.0, 66.0, 77.0, 88.0 },
					x.toArray(), 1e-6);
			Assert.assertArrayEquals(new double[] { 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0 },
					y.toArray(), 1e-6);
		}
	}

	/**
	 * Assignments into two different small destinations from the same small inputs, run
	 * repeatedly, each carry their result back into their own destination only: a kernel
	 * reused for the second assignment limits its copy-back to the slice the shared compiled
	 * scope writes.
	 */
	@Test(timeout = 60000)
	public void reusedKernelCopiesBackItsOwnDestination() {
		try (PackedCollection a = pack(1.0, 2.0, 3.0, 4.0);
			 PackedCollection b = pack(10.0, 20.0, 30.0, 40.0);
			 PackedCollection first = new PackedCollection(4);
			 PackedCollection second = new PackedCollection(4)) {
			OperationList op = new OperationList("two sums");
			op.add(new Assignment<>(1, p(first.each()), cp(a.each()).add(cp(b.each()))));
			op.add(new Assignment<>(1, p(second.each()), cp(b.each()).add(cp(a.each()))));

			Runnable run = op.get();
			for (int i = 0; i < 3; i++) {
				run.run();
			}

			double[] expected = { 11.0, 22.0, 33.0, 44.0 };
			Assert.assertArrayEquals(expected, first.toArray(), 1e-6);
			Assert.assertArrayEquals(expected, second.toArray(), 1e-6);
			Assert.assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 4.0 }, a.toArray(), 1e-6);
			Assert.assertArrayEquals(new double[] { 10.0, 20.0, 30.0, 40.0 }, b.toArray(), 1e-6);
		}
	}

	/**
	 * A statement that implements only what {@link Statement} requires, leaving what it writes
	 * and reads to the interface defaults.
	 */
	private static class UnaccountedStatement implements Statement<UnaccountedStatement> {
		@Override
		public String getStatement(LanguageOperations lang) { return "external();"; }

		@Override
		public List<Variable<?, ?>> getDependencies() { return Collections.emptyList(); }

		@Override
		public UnaccountedStatement simplify(KernelStructureContext context, int depth) { return this; }

		@Override
		public UnaccountedStatement replace(Expression target, Expression replacement) { return this; }
	}
}
