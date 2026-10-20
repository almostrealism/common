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

import io.almostrealism.code.Precision;
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.scope.Cases;
import io.almostrealism.scope.Scope;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.hardware.metal.MetalPrintWriter;
import org.almostrealism.io.PrintStreamPrintWriter;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Tests the branch conditions a {@link Cases} scope writes into generated source, in
 * languages that represent booleans differently: C, where they are integers, and Metal,
 * which has a {@code bool} of its own.
 */
public class CasesTests extends TestSuiteBase {

	/**
	 * Returns a {@link Cases} scope with a single empty branch guarded by a constant.
	 *
	 * @param condition the value of the branch condition
	 * @return a new scope with one branch
	 */
	private Cases<Void> cases(boolean condition) {
		Cases<Void> cases = new Cases<>("check");
		cases.addCase(new BooleanConstant(condition), new Scope<>("branch"), null);
		return cases;
	}

	/**
	 * Returns a C writer that writes generated source to {@code out}.
	 *
	 * @param out the destination of the generated source
	 * @return a new C writer
	 */
	private CodePrintWriter c(OutputStream out) {
		return new CPrintWriter(out, "casesTest", Precision.FP64);
	}

	/**
	 * Returns a Metal writer that writes generated source to {@code out}.
	 *
	 * @param out the destination of the generated source
	 * @return a new Metal writer
	 */
	private CodePrintWriter metal(OutputStream out) {
		return new MetalPrintWriter(new PrintStreamPrintWriter(new PrintStream(out)),
				"casesTest", Precision.FP32);
	}

	/**
	 * Asserts that writing a branch whose condition is known to be false is refused.
	 *
	 * @param writer the writer to attempt the branch with
	 */
	private void assertRefused(CodePrintWriter writer) {
		try {
			cases(false).write(writer);
			Assert.fail("A branch that can never be taken should be refused by " +
					writer.getClass().getSimpleName());
		} catch (UnsupportedOperationException expected) {
			// the branch is refused
		}
	}

	/**
	 * A branch whose condition is known to be false could never be taken, so writing it
	 * is refused in every language, including C, where that condition is written
	 * {@code 0} rather than {@code false}.
	 */
	@Test(timeout = 30000)
	public void knownFalseBranchIsRefusedInEveryLanguage() {
		assertRefused(c(new ByteArrayOutputStream()));
		assertRefused(metal(new ByteArrayOutputStream()));
	}

	/**
	 * A condition known to be true is written as the boolean literal of the target
	 * language: {@code 1} in C, where booleans are integers, and {@code true} in Metal.
	 */
	@Test(timeout = 30000)
	public void conditionIsWrittenInTheTargetLanguage() {
		ByteArrayOutputStream cSource = new ByteArrayOutputStream();
		cases(true).write(c(cSource));
		Assert.assertTrue(cSource.toString(), cSource.toString().contains("if (1) {"));

		ByteArrayOutputStream metalSource = new ByteArrayOutputStream();
		cases(true).write(metal(metalSource));
		Assert.assertTrue(metalSource.toString(), metalSource.toString().contains("if (true) {"));
	}
}
