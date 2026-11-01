/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.Hardware;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.internal.AssumptionViolatedException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Verifies the missing-resource policy of {@link TestFeatures#requireResource(String, String, boolean)}
 * and {@link TestFeatures#requireLocalResource(String, String, boolean)}: a present resource lets the
 * test proceed, a resource whose location is declared on this host fails when absent, an absent
 * resource fails on a GPU host under {@code requireResource}, and otherwise the test is skipped,
 * never passed. {@link TestFeatures#requireResourceDirectory(String, String, File)} follows the same
 * policy and additionally fails, on every host, for a directory that exists but is empty. The
 * overloads taking {@link ComputeRequirement}s let a test choose the hosts on which a missing
 * resource is not tolerable.
 */
public class ResourceRequirementTest extends TestSuiteBase {

	/** A location property declared by the tests that need one, and cleared after each test. */
	private static final String DECLARED_PROPERTY = "AR_TEST_RESOURCE_REQUIREMENT_LOCATION";

	/** A location property that is never declared. */
	private static final String UNDECLARED_PROPERTY = "AR_TEST_RESOURCE_REQUIREMENT_UNDECLARED";

	/** Clears the declared location property. */
	@After
	public void clearDeclaration() {
		System.clearProperty(DECLARED_PROPERTY);
	}

	/** A present resource returns normally under both requirements. */
	@Test(timeout = 30_000)
	public void presentResourceProceeds() {
		requireResource("present resource", UNDECLARED_PROPERTY, true);
		requireLocalResource("present resource", UNDECLARED_PROPERTY, true);
	}

	/** An absent resource whose location is declared fails under {@code requireResource}. */
	@Test(timeout = 30_000)
	public void declaredMissingResourceFails() {
		System.setProperty(DECLARED_PROPERTY, "/nonexistent");
		Assert.assertTrue(thrownBy(() -> requireResource("declared resource", DECLARED_PROPERTY, false))
				instanceof AssertionError);
	}

	/** An absent resource whose location is declared fails under {@code requireLocalResource}. */
	@Test(timeout = 30_000)
	public void declaredMissingLocalResourceFails() {
		System.setProperty(DECLARED_PROPERTY, "/nonexistent");
		Assert.assertTrue(thrownBy(() -> requireLocalResource("declared local resource", DECLARED_PROPERTY, false))
				instanceof AssertionError);
	}

	/**
	 * An absent, undeclared resource fails under {@code requireResource} on a GPU host, and is
	 * skipped (an assumption failure, never a normal return) on a host with no GPU.
	 */
	@Test(timeout = 30_000)
	public void undeclaredMissingResourceFailsOnlyWithGpu() {
		Class<? extends Throwable> expected = isGpuAvailable()
				? AssertionError.class : AssumptionViolatedException.class;
		Throwable thrown = thrownBy(() -> requireResource("undeclared resource", UNDECLARED_PROPERTY, false));
		Assert.assertTrue("expected " + expected.getSimpleName() + " but was " + thrown,
				expected.isInstance(thrown));
	}

	/**
	 * A test that names both {@link ComputeRequirement#GPU} and {@link ComputeRequirement#CPU} as
	 * the requirements that make its resource mandatory fails on every host when the resource is
	 * absent and undeclared, since every host provides at least one of them.
	 */
	@Test(timeout = 30_000)
	public void strictMissingResourceFailsOnEveryHost() {
		Throwable thrown = thrownBy(() -> requireResource("strict resource", UNDECLARED_PROPERTY, false,
				ComputeRequirement.GPU, ComputeRequirement.CPU));
		Assert.assertTrue("expected AssertionError but was " + thrown, thrown instanceof AssertionError);
		Assert.assertTrue(thrown.getMessage(), thrown.getMessage().contains("is available, so this host is meant to carry it"));
	}

	/**
	 * A resource made mandatory only where a requirement no host provides is available (an FPGA)
	 * is skipped when absent and undeclared, and still fails when its location is declared.
	 */
	@Test(timeout = 30_000)
	public void missingResourceSkipsWhereNoNamedRequirementIsAvailable() {
		Assert.assertFalse(Hardware.getLocalHardware().isAvailable(ComputeRequirement.FPGA));
		Assert.assertTrue(thrownBy(() -> requireResource("FPGA resource", UNDECLARED_PROPERTY, false,
				ComputeRequirement.FPGA)) instanceof AssumptionViolatedException);

		System.setProperty(DECLARED_PROPERTY, "/nonexistent");
		Assert.assertTrue(thrownBy(() -> requireResource("FPGA resource", DECLARED_PROPERTY, false,
				ComputeRequirement.FPGA)) instanceof AssertionError);
	}

	/**
	 * With no requirements, only a declared location makes a resource mandatory, exactly as
	 * {@code requireLocalResource} decides, and a present resource proceeds.
	 */
	@Test(timeout = 30_000)
	public void noRequirementsBehavesAsLocalResource() {
		requireResource("present resource", UNDECLARED_PROPERTY, true, new ComputeRequirement[0]);
		Assert.assertTrue(thrownBy(() -> requireResource("unrequired resource", UNDECLARED_PROPERTY, false,
				new ComputeRequirement[0])) instanceof AssumptionViolatedException);
	}

	/**
	 * A strict directory requirement fails on every host for a missing directory, and returns a
	 * populated one unchanged.
	 */
	@Test(timeout = 30_000)
	public void strictMissingDirectoryFailsOnEveryHost() throws IOException {
		Throwable thrown = thrownBy(() -> requireResourceDirectory("strict directory", UNDECLARED_PROPERTY,
				new File("/nonexistent/resource-requirement"), ComputeRequirement.GPU, ComputeRequirement.CPU));
		Assert.assertTrue("expected AssertionError but was " + thrown, thrown instanceof AssertionError);

		File directory = Files.createTempDirectory("resource-requirement").toFile();
		File entry = new File(directory, "entry.txt");
		try {
			Assert.assertTrue(entry.createNewFile());
			Assert.assertSame(directory, requireResourceDirectory("strict directory", UNDECLARED_PROPERTY,
					directory, ComputeRequirement.GPU, ComputeRequirement.CPU));
		} finally {
			entry.delete();
			directory.delete();
		}
	}

	/**
	 * A resource located by property returns its directory when it holds the file, and under a
	 * strict requirement fails on every host when it does not.
	 */
	@Test(timeout = 30_000)
	public void resourceInDirectoryIsLocatedAndRequired() throws IOException {
		File directory = Files.createTempDirectory("resource-requirement").toFile();
		File entry = new File(directory, "entry.txt");
		try {
			Assert.assertTrue(entry.createNewFile());
			Assert.assertEquals(directory.getAbsoluteFile(), requireResourceIn(UNDECLARED_PROPERTY,
					directory.getPath(), "entry.txt", ComputeRequirement.GPU, ComputeRequirement.CPU).getAbsoluteFile());

			Throwable thrown = thrownBy(() -> requireResourceIn(UNDECLARED_PROPERTY, directory.getPath(),
					"absent.txt", ComputeRequirement.GPU, ComputeRequirement.CPU));
			Assert.assertTrue("expected AssertionError but was " + thrown, thrown instanceof AssertionError);
			Assert.assertTrue(thrown.getMessage(), thrown.getMessage().contains("absent.txt in "));
		} finally {
			entry.delete();
			directory.delete();
		}
	}

	/** An absent, undeclared local resource is skipped on every host, GPU or not. */
	@Test(timeout = 30_000)
	public void undeclaredMissingLocalResourceSkips() {
		Assert.assertTrue(thrownBy(() -> requireLocalResource("undeclared local resource", null, false))
				instanceof AssumptionViolatedException);
	}

	/** A directory with at least one entry is returned unchanged. */
	@Test(timeout = 30_000)
	public void populatedDirectoryProceeds() throws IOException {
		File directory = Files.createTempDirectory("resource-requirement").toFile();
		File entry = new File(directory, "entry.txt");
		try {
			Assert.assertTrue(entry.createNewFile());
			Assert.assertSame(directory, requireResourceDirectory("populated directory", UNDECLARED_PROPERTY, directory));
		} finally {
			entry.delete();
			directory.delete();
		}
	}

	/**
	 * A directory that exists but is empty fails on every host, even one that would skip a
	 * missing resource: neither a declared location nor a GPU is needed to make it a failure.
	 */
	@Test(timeout = 30_000)
	public void emptyDirectoryFailsEvenWhenUndeclared() throws IOException {
		File directory = Files.createTempDirectory("resource-requirement").toFile();
		try {
			Throwable thrown = thrownBy(() -> requireResourceDirectory("empty directory", null, directory));
			Assert.assertTrue("expected AssertionError but was " + thrown, thrown instanceof AssertionError);
			Assert.assertTrue(thrown.getMessage(), thrown.getMessage().contains("exists but is empty"));
		} finally {
			directory.delete();
		}
	}

	/** A missing directory whose location is declared fails. */
	@Test(timeout = 30_000)
	public void declaredMissingDirectoryFails() {
		System.setProperty(DECLARED_PROPERTY, "/nonexistent");
		Throwable thrown = thrownBy(() -> requireResourceDirectory("declared directory",
				DECLARED_PROPERTY, new File("/nonexistent/resource-requirement")));
		Assert.assertTrue("expected AssertionError but was " + thrown, thrown instanceof AssertionError);
		Assert.assertFalse(thrown.getMessage(), thrown.getMessage().contains("exists but is empty"));
	}

	/** A file in place of the directory is treated as a missing directory, not as populated. */
	@Test(timeout = 30_000)
	public void fileInPlaceOfDirectoryFailsWhenDeclared() throws IOException {
		File file = Files.createTempFile("resource-requirement", ".txt").toFile();
		try {
			System.setProperty(DECLARED_PROPERTY, file.getAbsolutePath());
			Assert.assertTrue(thrownBy(() -> requireResourceDirectory("declared directory", DECLARED_PROPERTY, file))
					instanceof AssertionError);
		} finally {
			file.delete();
		}
	}

	/**
	 * A missing, undeclared directory fails on a GPU host and is skipped on a host with no GPU,
	 * exactly as {@code requireResource} decides.
	 */
	@Test(timeout = 30_000)
	public void undeclaredMissingDirectoryFailsOnlyWithGpu() {
		Class<? extends Throwable> expected = isGpuAvailable()
				? AssertionError.class : AssumptionViolatedException.class;
		Throwable thrown = thrownBy(() -> requireResourceDirectory("undeclared directory",
				UNDECLARED_PROPERTY, new File("/nonexistent/resource-requirement")));
		Assert.assertTrue("expected " + expected.getSimpleName() + " but was " + thrown,
				expected.isInstance(thrown));
	}

	/**
	 * Runs {@code check} and returns what it threw, failing if it returned normally: a
	 * missing resource must never let a test proceed as if it were present.
	 *
	 * @param check the resource check to run
	 * @return the throwable {@code check} threw
	 */
	private Throwable thrownBy(Runnable check) {
		try {
			check.run();
		} catch (Throwable t) {
			return t;
		}

		throw new AssertionError("a missing resource returned normally");
	}
}
