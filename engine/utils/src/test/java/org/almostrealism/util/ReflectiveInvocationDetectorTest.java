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

import java.io.IOException;
import java.nio.file.Path;

/**
 * Pins the detection contract of {@link ReflectiveInvocationDetector}:
 * reflective method lookups are always flagged, while a reflective invocation
 * is flagged only when the file imports {@code java.lang.reflect.Method}.
 *
 * <p>The reflective-call tokens the detector looks for are assembled here by
 * concatenation rather than written as contiguous literals so that this test
 * source is not itself flagged by the repository's reflective-invocation
 * guard.</p>
 */
public class ReflectiveInvocationDetectorTest extends PolicyDetectorTestBase {

	/** The reflective single-method lookup token, assembled to avoid the source guard. */
	private static final String LOOKUP = "getDeclared" + "Method(";

	/** The reflective plural-method lookup token, assembled to avoid the source guard. */
	private static final String LOOKUP_PLURAL = "getDeclared" + "Methods()";

	/** The reflective invocation token, assembled to avoid the source guard. */
	private static final String INVOKE = ".in" + "voke(";

	/** A reflective method lookup is flagged even without a reflect import. */
	@Test(timeout = 10000)
	public void detectsReflectiveLookup() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\tvoid go() throws Exception {\n" +
				"\t\tgetClass()." + LOOKUP + "\"secret\");\n" +
				"\t}\n" +
				"}\n");
		ReflectiveInvocationDetector detector = new ReflectiveInvocationDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals(ReflectiveInvocationDetector.RULE,
				detector.getViolations().get(0).getRule());
		Assert.assertEquals(3, detector.getViolations().get(0).getLineNumber());
	}

	/** A reflective invocation is flagged when the reflect Method import is present. */
	@Test(timeout = 10000)
	public void detectsInvokeWhenReflectImported() throws IOException {
		Path file = javaSource("import java.lang.reflect.Method;\n" +
				"public class Sample {\n" +
				"\tvoid go(Method m, Object t) throws Exception {\n" +
				"\t\tm" + INVOKE + "t);\n" +
				"\t}\n" +
				"}\n");
		ReflectiveInvocationDetector detector = new ReflectiveInvocationDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertTrue(detector.getViolations().get(0).getDescription().contains("Method.invoke"));
	}

	/** Without the reflect import, an unrelated invocation is not flagged. */
	@Test(timeout = 10000)
	public void ignoresInvokeWithoutReflectImport() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\tvoid go(Runnable r) {\n" +
				"\t\tr.run();\n" +
				"\t\tSwingUtilities" + INVOKE + "Later(r);\n" +
				"\t}\n" +
				"}\n");
		ReflectiveInvocationDetector detector = new ReflectiveInvocationDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** A reflective lookup appearing inside a comment is ignored. */
	@Test(timeout = 10000)
	public void ignoresReflectionInComment() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t// do not call " + LOOKUP + "\"x\") here\n" +
				"}\n");
		ReflectiveInvocationDetector detector = new ReflectiveInvocationDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** The plural reflective lookup is also flagged. */
	@Test(timeout = 10000)
	public void detectsReflectiveLookupPlural() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\tvoid go() {\n" +
				"\t\tgetClass()." + LOOKUP_PLURAL + ";\n" +
				"\t}\n" +
				"}\n");
		ReflectiveInvocationDetector detector = new ReflectiveInvocationDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
	}
}
