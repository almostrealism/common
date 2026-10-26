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

package io.flowtree.jobs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;

import static io.flowtree.jobs.ObjectOrientedRiskGate.Signal;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests of {@link ObjectOrientedRiskGate}: each design signal fires on the shape it names and stays
 * silent on ordinary code, so the gate selects classes worth a review session and leaves the rest.
 */
public class ObjectOrientedRiskGateTest extends TestSuiteBase {

	/** The gate under test. */
	private final ObjectOrientedRiskGate gate = new ObjectOrientedRiskGate();

	/** A new class fires {@link Signal#NEW_CLASS} regardless of its contents. */
	@Test(timeout = 30000)
	public void newClassFires() {
		EnumSet<Signal> signals = gate.evaluate("SlidingWindow", true, List.of("public class SlidingWindow {"), null);
		assertTrue(signals.contains(Signal.NEW_CLASS));
	}

	/** A name ending in an assertive suffix fires {@link Signal#SUSPICIOUS_NAME}. */
	@Test(timeout = 30000)
	public void assertiveNamesFire() {
		for (String name : List.of("StringUtil", "AudioHelper", "TransformerModel", "AttentionBlock", "LayerConfig")) {
			assertTrue(name, gate.evaluate(name, false, List.of(), null).contains(Signal.SUSPICIOUS_NAME));
		}
		assertFalse(gate.evaluate("SlidingWindow", false, List.of(), null).contains(Signal.SUSPICIOUS_NAME));
		assertFalse("Configuration must not match the Config suffix",
				gate.evaluate("Configuration", false, List.of(), null).contains(Signal.SUSPICIOUS_NAME));
	}

	/** An added static member fires {@link Signal#NEW_STATIC}; an instance member does not. */
	@Test(timeout = 30000)
	public void newStaticFires() {
		assertTrue(gate.evaluate("Thing", false,
				List.of("    public static void help() {"), null).contains(Signal.NEW_STATIC));
		assertTrue(gate.evaluate("Thing", false,
				List.of("    static final int LIMIT = 4;"), null).contains(Signal.NEW_STATIC));
		assertFalse(gate.evaluate("Thing", false,
				List.of("    private final int count = 4;"), null).contains(Signal.NEW_STATIC));
		assertFalse("a reference to a static member is not a static declaration",
				gate.evaluate("Thing", false,
						List.of("        return Math.max(0, n);"), null).contains(Signal.NEW_STATIC));
	}

	/** An added instanceof test or cast fires {@link Signal#INSTANCEOF_OR_CAST}. */
	@Test(timeout = 30000)
	public void instanceofAndCastFire() {
		assertTrue(gate.evaluate("Thing", false,
				List.of("        if (x instanceof Foo) {"), null).contains(Signal.INSTANCEOF_OR_CAST));
		assertTrue(gate.evaluate("Thing", false,
				List.of("        Foo f = (Foo) x;"), null).contains(Signal.INSTANCEOF_OR_CAST));
		assertFalse("an ordinary call is not a cast",
				gate.evaluate("Thing", false,
						List.of("        compute(value);"), null).contains(Signal.INSTANCEOF_OR_CAST));
		assertFalse("a conditional is not a cast",
				gate.evaluate("Thing", false,
						List.of("        if (ready) {"), null).contains(Signal.INSTANCEOF_OR_CAST));
	}

	/** A lambda or method reference passed as an argument fires {@link Signal#LAMBDA_ARGUMENT}. */
	@Test(timeout = 30000)
	public void lambdaArgumentFires() {
		assertTrue(gate.evaluate("Thing", false,
				List.of("        config.setSink(value -> accept(value));"), null).contains(Signal.LAMBDA_ARGUMENT));
		assertTrue(gate.evaluate("Thing", false,
				List.of("        register(this::handle);"), null).contains(Signal.LAMBDA_ARGUMENT));
		assertFalse("an ordinary assignment is not a call argument",
				gate.evaluate("Thing", false,
						List.of("        int total = a + b;"), null).contains(Signal.LAMBDA_ARGUMENT));
	}

	/**
	 * A public method whose parameter type the class already holds as a field fires
	 * {@link Signal#DERIVABLE_PARAMETER}; a parameter of an unrelated type does not.
	 */
	@Test(timeout = 30000)
	public void derivableParameterFires() {
		String content = "class Generator {\n"
				+ "    private final CompiledModel inference;\n"
				+ "    private final Random random;\n"
				+ "}\n";
		assertTrue(gate.evaluate("Generator", false,
				List.of("    public String generate(CompiledModel inference) {"), content)
				.contains(Signal.DERIVABLE_PARAMETER));
		assertFalse(gate.evaluate("Generator", false,
				List.of("    public String generate(String prompt) {"), content)
				.contains(Signal.DERIVABLE_PARAMETER));
		assertFalse("without the class content the signal cannot be judged",
				gate.evaluate("Generator", false,
						List.of("    public String generate(CompiledModel inference) {"), null)
						.contains(Signal.DERIVABLE_PARAMETER));
	}

	/** Ordinary, well-shaped added code on an unremarkable name fires nothing. */
	@Test(timeout = 30000)
	public void cleanClassFiresNoSignals() {
		String content = "class SlidingWindow {\n    private final int size;\n}\n";
		EnumSet<Signal> signals = gate.evaluate("SlidingWindow", false,
				List.of("    public int size() {", "        return size;", "    }"), content);
		assertEquals(EnumSet.noneOf(Signal.class), signals);
	}

	/** A comment's words are not read as code, so a cast mentioned in a comment does not fire. */
	@Test(timeout = 30000)
	public void commentsAreNotCode() {
		EnumSet<Signal> signals = gate.evaluate("Thing", false,
				List.of("        // this used to do (Foo) x instanceof checks"), null);
		assertFalse(signals.contains(Signal.INSTANCEOF_OR_CAST));
	}
}
