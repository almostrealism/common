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
 * Pins the detection contract of {@link ProducerPatternDetector}: within a
 * computation source tree ({@code /ml/src/main/java/} or {@code /studio/}),
 * {@code .evaluate()} and {@code .toDouble()} calls in non-boundary methods
 * are flagged; the same calls outside a computation tree are not.
 */
public class ProducerPatternDetectorTest extends PolicyDetectorTestBase {

	/** {@code .evaluate()} inside an ml-tree computation method is flagged. */
	@Test(timeout = 10000)
	public void detectsEvaluateInComputationTree() throws IOException {
		Path file = writeSourceAt("ml/src/main/java/Sample.java",
				"public class Sample {\n" +
				"\tvoid forward() {\n" +
				"\t\tresult.evaluate();\n" +
				"\t}\n" +
				"}\n");
		ProducerPatternDetector detector = new ProducerPatternDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals("PRODUCER_EVALUATE_IN_COMPUTATION",
				detector.getViolations().get(0).getRule());
		Assert.assertEquals(3, detector.getViolations().get(0).getLineNumber());
	}

	/** {@code .toDouble()} inside an ml-tree computation method is flagged. */
	@Test(timeout = 10000)
	public void detectsToDoubleInComputationTree() throws IOException {
		Path file = writeSourceAt("ml/src/main/java/Sample.java",
				"public class Sample {\n" +
				"\tvoid forward() {\n" +
				"\t\tdouble d = weights.toDouble(0);\n" +
				"\t}\n" +
				"}\n");
		ProducerPatternDetector detector = new ProducerPatternDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals("PRODUCER_TODOUBLE_IN_COMPUTATION",
				detector.getViolations().get(0).getRule());
	}

	/** The same {@code .evaluate()} outside any computation tree is not flagged. */
	@Test(timeout = 10000)
	public void ignoresEvaluateOutsideComputationTree() throws IOException {
		Path file = writeSourceAt("app/src/main/java/Sample.java",
				"public class Sample {\n" +
				"\tvoid forward() {\n" +
				"\t\tresult.evaluate();\n" +
				"\t}\n" +
				"}\n");
		ProducerPatternDetector detector = new ProducerPatternDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** {@code .evaluate()} in {@code main} is a sanctioned call-stack boundary. */
	@Test(timeout = 10000)
	public void ignoresEvaluateInMain() throws IOException {
		Path file = writeSourceAt("ml/src/main/java/Sample.java",
				"public class Sample {\n" +
				"\tpublic static void main(String[] args) {\n" +
				"\t\tresult.evaluate();\n" +
				"\t}\n" +
				"}\n");
		ProducerPatternDetector detector = new ProducerPatternDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/**
	 * A compiled operation hidden inside a nested lambda is flagged. The
	 * {@code get().run(} token is assembled from fragments so this test source
	 * is not itself flagged by the repository's hidden-operation enforcement.
	 */
	@Test(timeout = 10000)
	public void detectsHiddenOperationLambda() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\tvoid wire() {\n" +
				"\t\tSupplier s = x -> () -> op.get()." + "run();\n" +
				"\t}\n" +
				"}\n");
		ProducerPatternDetector detector = new ProducerPatternDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals("HIDDEN_OPERATION_LAMBDA",
				detector.getViolations().get(0).getRule());
	}
}
