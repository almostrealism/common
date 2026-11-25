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

package org.almostrealism.algebra;

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.Statement;
import io.almostrealism.expression.ArrayDeclaration;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.Mask;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Function;

import static org.almostrealism.algebra.LoopInvariantHoistingTest.countDeclarationsNamed;

/**
 * Tests that loop-invariant code motion in {@link Repeated} is sound under buffer aliasing: a
 * declaration that reads a buffer the loop may write is loop-variant and must stay inside the loop,
 * however the write is made (a direct element write, a call, a masked destination, a write among
 * the loop's own statements, or a write to an array declared inside the loop), while a read of a
 * buffer the loop never writes is still hoisted.
 *
 * @see Repeated
 * @see LoopInvariantHoistingTest
 */
public class LoopInvariantBufferAliasingTest extends TestSuiteBase {

	/**
	 * Verifies that a declaration which reads an element of a buffer the loop writes is NOT
	 * hoisted, even though the read registers no loop-variant <em>name</em>. The write and the
	 * read both go through an {@link InstanceReference} to the same {@link ArrayVariable}: the
	 * write records the buffer only by name, while an element read contributes no name at all, so
	 * the name-based variance analysis judges the read invariant. Keying variance on the written
	 * buffer's identity catches it.
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i &lt; 100; i++) {
	 *   {
	 *     buffer[i] = i;                   // non-declaration write — mutates buffer each iteration
	 *     double f_alias = buffer[0] + 1.0; // reads the mutated buffer — must stay in the loop
	 *   }
	 * }
	 * </pre>
	 *
	 * <p>This test fails before the aliasing fix (the declaration is hoisted and reads a stale
	 * value) and passes after it.</p>
	 */
	@Test(timeout = 30000)
	public void declarationReadingLoopWrittenBufferNotHoisted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("bufferAliasTest");

		// A buffer the loop both writes and reads.
		ArrayVariable<Double> buffer = new ArrayVariable<>(Double.class, "_v_alias", e(8));

		Scope<Void> child = new Scope<>("body");

		// Non-declaration write: buffer[i] = i (mutates the buffer on every iteration). The
		// loop-variant position keeps this a plain loop rather than an accumulator promotion.
		child.getStatements().add(new ExpressionAssignment(
				false,
				new InstanceReference<>(buffer, idx.ref(), null),
				new StaticReference<>(Double.class, "_test_i")));

		// Declaration reading the same buffer at a constant position: f_alias = buffer[0] + 1.0.
		// The read contributes no name, so without identity-keyed variance it looks invariant.
		child.getStatements().add(new ExpressionAssignment(
				true,
				new StaticReference<>(Double.class, "f_alias"),
				new InstanceReference<>(buffer, e(0), null).add(new DoubleConstant(1.0))));

		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		Assert.assertEquals("Declaration reading a buffer the loop writes must NOT be hoisted",
				0, countDeclarationsNamed(simplified.getStatements(), "f_alias"));
	}

	/**
	 * Verifies that a declaration reading a buffer which the loop passes to a call is NOT hoisted.
	 * A {@link Method} call runs a body that is not part of the scope, so it may write any array it
	 * is given; the loop writes the buffer without any assignment naming it as a destination.
	 *
	 * <pre>
	 * for (i = 0; i &lt; 100; i++) {
	 *   {
	 *     update(buffer[i]);                 // opaque call — may write buffer
	 *     double f_alias = buffer[0] + 1.0;  // must stay in the loop
	 *   }
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void declarationReadingBufferWrittenByCallNotHoisted() {
		ArrayVariable<Double> buffer = new ArrayVariable<>(Double.class, "_v_call", e(8));
		Repeated<Void> simplified = simplifyBufferLoop(
				idx -> new Method<>(Double.class, "update", new InstanceReference<>(buffer, idx.ref(), null)),
				new InstanceReference<>(buffer, e(0), null).add(new DoubleConstant(1.0)));

		Assert.assertEquals("Declaration reading a buffer a call in the loop may write must NOT be hoisted",
				0, countDeclarationsNamed(simplified.getStatements(), "f_alias"));
	}

	/**
	 * Verifies that a declaration reading a buffer which the loop writes through a masked
	 * destination is NOT hoisted. The destination is a {@link Mask} wrapping the element reference,
	 * so the written buffer is reached only through the wrapper's children.
	 *
	 * <pre>
	 * for (i = 0; i &lt; 100; i++) {
	 *   {
	 *     (i &lt; 50 ? buffer[i] : 0) = i;     // masked write
	 *     double f_alias = buffer[0] + 1.0;  // must stay in the loop
	 *   }
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void declarationReadingBufferWrittenThroughMaskNotHoisted() {
		ArrayVariable<Double> buffer = new ArrayVariable<>(Double.class, "_v_mask", e(8));
		Repeated<Void> simplified = simplifyBufferLoop(
				idx -> new ExpressionAssignment(false,
						Mask.of(idx.ref().lessThan(50), new InstanceReference<>(buffer, idx.ref(), null)),
						new StaticReference<>(Double.class, "_test_i")),
				new InstanceReference<>(buffer, e(0), null).add(new DoubleConstant(1.0)));

		Assert.assertEquals("Declaration reading a buffer written through a masked destination must NOT be hoisted",
				0, countDeclarationsNamed(simplified.getStatements(), "f_alias"));
	}

	/**
	 * Verifies that a declaration reading a buffer the loop writes through a {@link StaticReference}
	 * whose referent is that buffer is NOT hoisted. The reference names no element and has no
	 * children, so only its referent tells that it reads the buffer.
	 *
	 * <pre>
	 * for (i = 0; i &lt; 100; i++) {
	 *   {
	 *     buffer[i] = i;
	 *     double f_alias = (opaque read of buffer) + 1.0;  // must stay in the loop
	 *   }
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void declarationReadingWrittenBufferThroughStaticReferenceNotHoisted() {
		ArrayVariable<Double> buffer = new ArrayVariable<>(Double.class, "_v_static", e(8));
		Repeated<Void> simplified = simplifyBufferLoop(
				idx -> bufferWrite(buffer, idx),
				new StaticReference<>(Double.class, "opaque_read", buffer).add(new DoubleConstant(1.0)));

		Assert.assertEquals("Declaration reading a written buffer through a static reference must NOT be hoisted",
				0, countDeclarationsNamed(simplified.getStatements(), "f_alias"));
	}

	/**
	 * Verifies that the written-buffer check is not over-conservative: a declaration reading a
	 * buffer the loop never writes is still hoisted, even though the loop writes another buffer.
	 */
	@Test(timeout = 30000)
	public void declarationReadingUnwrittenBufferIsHoisted() {
		ArrayVariable<Double> written = new ArrayVariable<>(Double.class, "_v_written", e(8));
		ArrayVariable<Double> unwritten = new ArrayVariable<>(Double.class, "_v_unwritten", e(8));
		Repeated<Void> simplified = simplifyBufferLoop(
				idx -> bufferWrite(written, idx),
				new InstanceReference<>(unwritten, e(0), null).add(new DoubleConstant(1.0)));

		Assert.assertEquals("Declaration reading a buffer the loop does not write should be hoisted",
				1, countDeclarationsNamed(simplified.getStatements(), "f_alias"));
	}

	/**
	 * Verifies that a write among the loop scope's <em>own</em> statements (not inside a child)
	 * still blocks hoisting of a child declaration that reads that buffer. The accumulation builder
	 * emits such writes with {@code loop.getStatements().add(...)}, and the hoisted declarations are
	 * inserted ahead of them, so a read hoisted past the write would see a stale value.
	 *
	 * <pre>
	 * buffer[0] = _test_i;                 // loop's own statement
	 * for (i = 0; i &lt; 100; i++) {
	 *   { double f_alias = buffer[0] + 1.0; }  // must stay in the loop
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void declarationReadingBufferWrittenByOwnStatementNotHoisted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("ownStatementWriteTest");

		ArrayVariable<Double> buffer = new ArrayVariable<>(Double.class, "_v_own", e(8));

		// Write among the loop's own statements, not within a child scope.
		loop.getStatements().add(new ExpressionAssignment(false,
				new InstanceReference<>(buffer, e(0), null),
				new StaticReference<>(Double.class, "_test_i")));

		Scope<Void> child = new Scope<>("body");
		child.getStatements().add(new ExpressionAssignment(true,
				new StaticReference<>(Double.class, "f_alias"),
				new InstanceReference<>(buffer, e(0), null).add(new DoubleConstant(1.0))));
		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		Assert.assertEquals("Declaration reading a buffer the loop writes in its own statements must NOT be hoisted",
				0, countDeclarationsNamed(simplified.getStatements(), "f_alias"));
	}

	/**
	 * Builds and simplifies a loop over {@code _test_i} whose body performs {@code write} and then
	 * declares {@code f_alias = read}, the shape shared by the buffer-aliasing tests.
	 *
	 * @param write produces the writing statement from the loop index
	 * @param read  the expression the {@code f_alias} declaration evaluates
	 * @return the simplified loop, whose own statements are the hoisted declarations
	 */
	private Repeated<Void> simplifyBufferLoop(Function<Variable<Integer, ?>, Statement<?>> write,
											  Expression<?> read) {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("bufferAliasTest");

		Scope<Void> child = new Scope<>("body");
		child.getStatements().add(write.apply(idx));
		child.getStatements().add(new ExpressionAssignment(
				true, new StaticReference<>(Double.class, "f_alias"), read));
		loop.getChildren().add(child);

		return (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);
	}

	/**
	 * Returns the statement {@code buffer[i] = i}. The loop-variant position keeps the loop a plain
	 * loop rather than an accumulator promotion.
	 *
	 * @param buffer the buffer written
	 * @param idx    the loop index
	 * @return the write statement
	 */
	private static Statement<?> bufferWrite(ArrayVariable<Double> buffer, Variable<Integer, ?> idx) {
		return new ExpressionAssignment(false,
				new InstanceReference<>(buffer, idx.ref(), null),
				new StaticReference<>(Double.class, "_test_i"));
	}

	/**
	 * Verifies that a declaration reading an array declared among the loop scope's own statements,
	 * and written in the loop body, is NOT hoisted. The loop's dependencies omit an array it
	 * declares itself, so the written-array analysis must find such an array from its declaration;
	 * hoisting the read would both read a stale value and place it ahead of the declaration.
	 *
	 * <pre>
	 * double local[8];                     // loop's own statement
	 * for (i = 0; i &lt; 100; i++) {
	 *   {
	 *     local[i] = i;
	 *     double f_alias = local[0] + 1.0;  // must stay in the loop
	 *   }
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void declarationReadingLoopDeclaredArrayNotHoisted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("loopDeclaredArrayTest");

		ArrayVariable<Double> local = (ArrayVariable<Double>) loop.declareArray("_v_local", e(8));
		loop.getChildren().add(writeThenRead(local, idx));

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		Assert.assertEquals("Declaration reading an array declared and written in the loop must NOT be hoisted",
				0, countDeclarationsNamed(simplified.getStatements(), "f_alias"));
		Assert.assertTrue("The loop must still declare its array",
				simplified.getStatements().get(0) instanceof ArrayDeclaration);
	}

	/**
	 * Verifies that a declaration reading an array declared in a child of the loop, and written in
	 * that child's own child, is NOT hoisted. The child's dependencies omit the array it declares,
	 * so the loop's dependencies omit it as well.
	 *
	 * <pre>
	 * for (i = 0; i &lt; 100; i++) {
	 *   {
	 *     double local[8];
	 *     {
	 *       local[i] = i;
	 *       double f_alias = local[0] + 1.0;  // must stay in the loop
	 *     }
	 *   }
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void declarationReadingArrayDeclaredInChildNotHoisted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("childDeclaredArrayTest");

		Scope<Void> child = new Scope<>("outer");
		ArrayVariable<Double> local = (ArrayVariable<Double>) child.declareArray("_v_nested", e(8));
		child.getChildren().add(writeThenRead(local, idx));
		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		Assert.assertEquals("Declaration reading an array declared inside the loop must NOT be hoisted",
				0, countDeclarationsNamed(simplified.getStatements(), "f_alias"));
	}

	/**
	 * Returns a scope that writes {@code array[i] = i} and then declares
	 * {@code f_alias = array[0] + 1.0}.
	 *
	 * @param array the array written and read
	 * @param idx   the loop index
	 * @return the body scope
	 */
	private Scope<Void> writeThenRead(ArrayVariable<Double> array, Variable<Integer, ?> idx) {
		Scope<Void> body = new Scope<>("body");
		body.getStatements().add(bufferWrite(array, idx));
		body.getStatements().add(new ExpressionAssignment(true,
				new StaticReference<>(Double.class, "f_alias"),
				new InstanceReference<>(array, e(0), null)
						.add(new DoubleConstant(1.0))));
		return body;
	}
}
