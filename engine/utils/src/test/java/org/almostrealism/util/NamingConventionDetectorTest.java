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
 * Pins the detection contract of {@link NamingConventionDetector}: a class
 * whose name ends in Cell or Block must implement the corresponding interface,
 * and an interface whose name ends in Features must expose only default (not
 * abstract) methods.
 *
 * <p>The class and interface declarations used as fixtures are assembled from
 * fragments so the declaration keyword and the suffixed type name are never
 * contiguous in this source; that keeps this test file from being flagged by
 * the repository's naming-convention enforcement while the detector under test
 * still receives the assembled, contiguous declaration.</p>
 */
public class NamingConventionDetectorTest extends PolicyDetectorTestBase {

	/** A Cell-suffixed class that does not implement the interface is flagged. */
	@Test(timeout = 10000)
	public void detectsCellNamingViolation() throws IOException {
		Path file = writeSource("WidgetCell.java", "public class Widget" + "Cell {\n}\n");
		NamingConventionDetector detector = new NamingConventionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals("CELL_NAMING_VIOLATION", detector.getViolations().get(0).getRule());
		Assert.assertTrue(detector.getViolations().get(0).getDescription().contains("WidgetCell"));
	}

	/** A Cell-suffixed class that implements the interface is not flagged. */
	@Test(timeout = 10000)
	public void ignoresCellThatImplementsInterface() throws IOException {
		Path file = writeSource("WidgetCell.java",
				"public class Widget" + "Cell implements Cell {\n}\n");
		NamingConventionDetector detector = new NamingConventionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** A Block-suffixed class that does not implement the interface is flagged. */
	@Test(timeout = 10000)
	public void detectsBlockNamingViolation() throws IOException {
		Path file = writeSource("FancyBlock.java", "public class Fancy" + "Block {\n}\n");
		NamingConventionDetector detector = new NamingConventionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals("BLOCK_NAMING_VIOLATION", detector.getViolations().get(0).getRule());
	}

	/** The specific CodeBlock name is an accepted exception. */
	@Test(timeout = 10000)
	public void ignoresCodeBlockException() throws IOException {
		Path file = writeSource("CodeBlock.java", "public class Code" + "Block {\n}\n");
		NamingConventionDetector detector = new NamingConventionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** A Features-suffixed interface declaring an abstract method is flagged. */
	@Test(timeout = 10000)
	public void detectsAbstractMethodInFeaturesInterface() throws IOException {
		Path file = writeSource("WidgetFeatures.java",
				"public interface Widget" + "Features {\n" +
				"\tvoid mustImplement();\n" +
				"}\n");
		NamingConventionDetector detector = new NamingConventionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals("FEATURES_INTERFACE_ABSTRACT_METHOD",
				detector.getViolations().get(0).getRule());
	}

	/** A Features-suffixed interface with only default methods is not flagged. */
	@Test(timeout = 10000)
	public void ignoresDefaultOnlyFeaturesInterface() throws IOException {
		Path file = writeSource("WidgetFeatures.java",
				"public interface Widget" + "Features {\n" +
				"\tdefault int size() { return 3; }\n" +
				"}\n");
		NamingConventionDetector detector = new NamingConventionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}
}
