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

package org.almostrealism.util;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * Pins the shared source-analysis helpers on {@link PolicyViolationDetector}
 * that every concrete detector relies on: enclosing-method resolution,
 * initialization-method classification, for-loop containment, line/character
 * counting, context extraction, bulk-copy recognition, interface-implementation
 * checks, and the path exclusion/test-source predicates.
 */
public class PolicyViolationDetectorHelpersTest extends TestSuiteBase {

	/**
	 * Minimal concrete detector used only to reach the protected helper methods
	 * of {@link PolicyViolationDetector} from within its package.
	 */
	private static final class Probe extends PolicyViolationDetector {
		/** Creates a probe rooted at the current directory; the root is never scanned. */
		private Probe() {
			super(Path.of("."));
		}

		/**
		 * No-op scan: this probe exists only to expose the protected helpers.
		 *
		 * @param file ignored
		 * @return this probe
		 */
		@Override
		public PolicyViolationDetector scanFile(Path file) {
			return this;
		}
	}

	/** A regular method body resolves to the enclosing method's name. */
	@Test(timeout = 10000)
	public void findsEnclosingMethodName() {
		Probe probe = new Probe();
		List<String> lines = List.of(
				"public void doThing() {",
				"\tint x = 0;",
				"}");
		Assert.assertEquals("doThing", probe.findEnclosingMethodName(lines, 1));
	}

	/** A constructor declaration (no return type) resolves to the constructor marker. */
	@Test(timeout = 10000)
	public void findsEnclosingConstructor() {
		Probe probe = new Probe();
		List<String> lines = List.of(
				"public Sample() {",
				"\tinitialize();",
				"}");
		Assert.assertEquals("<constructor>", probe.findEnclosingMethodName(lines, 1));
	}

	/** Initialization-style method names (and constructors) are classified as init code. */
	@Test(timeout = 10000)
	public void classifiesInitializationMethods() {
		Probe probe = new Probe();
		Assert.assertTrue(probe.isInitializationMethod("<constructor>"));
		Assert.assertTrue(probe.isInitializationMethod("computeWeights"));
		Assert.assertTrue(probe.isInitializationMethod("loadData"));
		Assert.assertFalse(probe.isInitializationMethod("transform"));
	}

	/** A line nested in a for-loop body is recognized; a plain method body is not. */
	@Test(timeout = 10000)
	public void recognizesForLoopContainment() {
		Probe probe = new Probe();
		List<String> inLoop = List.of(
				"for (int i = 0; i < 3; i++) {",
				"\tconsume(i);",
				"}");
		Assert.assertTrue(probe.isInsideForLoop(inLoop, 1));

		List<String> notLoop = List.of(
				"void m() {",
				"\tconsume(0);",
				"}");
		Assert.assertFalse(probe.isInsideForLoop(notLoop, 1));
	}

	/** Line and character counting are 1-based and exact. */
	@Test(timeout = 10000)
	public void countsLinesAndCharacters() {
		Probe probe = new Probe();
		String content = "l1\nl2\nl3";
		Assert.assertEquals(3, probe.countLines(content, content.indexOf("l3")));
		Assert.assertEquals(1, probe.countLines(content, 0));
		Assert.assertEquals(2, probe.countChar("a,b,c", ','));
	}

	/** Context extraction returns the window of lines within the given radius. */
	@Test(timeout = 10000)
	public void extractsContextWindow() {
		Probe probe = new Probe();
		List<String> lines = List.of("A", "B", "C", "D", "E");
		Assert.assertEquals("B\nC\nD\n", probe.getContext(lines, 2, 1));
	}

	/** A four-argument bulk copy is recognized; an element-wise toDouble store is not. */
	@Test(timeout = 10000)
	public void recognizesBulkCopyPattern() {
		Probe probe = new Probe();
		Assert.assertTrue(probe.isBulkCopyPattern("dest.setMem(0, src, 0, 4);"));
		Assert.assertFalse(probe.isBulkCopyPattern("dest.setMem(i, src.toDouble(i));"));
	}

	/** Interface-implementation detection recognizes both implements and known bases. */
	@Test(timeout = 10000)
	public void detectsInterfaceImplementation() {
		Probe probe = new Probe();
		Assert.assertTrue(probe.classImplementsInterface(
				"class WidgetCell implements Cell {", "WidgetCell", "Cell", List.of()));
		Assert.assertTrue(probe.classImplementsInterface(
				"class WidgetCell extends CellAdapter {", "WidgetCell", "Cell",
				List.of("CellAdapter")));
		Assert.assertFalse(probe.classImplementsInterface(
				"class WidgetCell {", "WidgetCell", "Cell", List.of()));
	}

	/** The exclusion predicate matches detector infrastructure and build output. */
	@Test(timeout = 10000)
	public void excludesInfrastructurePaths() {
		Probe probe = new Probe();
		Assert.assertTrue(probe.isExcluded(Path.of("x/PolicyViolationDetector.java")));
		Assert.assertTrue(probe.isExcluded(Path.of("x/target/classes/Foo.java")));
		Assert.assertFalse(probe.isExcluded(Path.of("x/Regular.java")));
	}

	/** The test-source predicate matches only Maven test trees. */
	@Test(timeout = 10000)
	public void identifiesTestSources() {
		Probe probe = new Probe();
		Assert.assertTrue(probe.isTestSource(Path.of("m/src/test/java/FooTest.java")));
		Assert.assertFalse(probe.isTestSource(Path.of("m/src/main/java/Foo.java")));
	}
}
