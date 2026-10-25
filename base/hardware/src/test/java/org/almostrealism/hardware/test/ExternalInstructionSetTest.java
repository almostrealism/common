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

package org.almostrealism.hardware.test;

import io.almostrealism.code.Execution;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.external.ExternalInstructionSet;
import org.almostrealism.hardware.external.LocalExternalMemoryProvider;
import org.almostrealism.hardware.mem.Bytes;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * An {@link Execution} obtained from an {@link ExternalInstructionSet} honors the work range
 * every {@link Execution} is dispatched over. The standalone executable it runs is given no
 * work-item index, so it accepts the single work item a dispatcher sets for a one-item kernel
 * and refuses any other range instead of silently performing only one item of it.
 *
 * <p>The work range tests compile and run nothing: the range is set before arguments are
 * accepted, so they never reach the process launch. The tests that do run an execution use a
 * shell script in place of the compiled executable, which is given the same data directory
 * and leaves its results in the same argument files.</p>
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}: that
 * class lives in the engine layer, which sits above this module.</p>
 */
public class ExternalInstructionSetTest {

	/**
	 * Returns an execution of an instruction set whose executable is never launched.
	 *
	 * @return an execution over one argument
	 */
	private Execution execution() {
		return new ExternalInstructionSet("never-launched", () -> null).get("function", 1);
	}

	/** The range a dispatcher sets for a one-item kernel is accepted and reported back. */
	@Test(timeout = 10000)
	public void acceptsSingleWorkItem() {
		Execution execution = execution();
		execution.setGlobalWorkOffset(0);
		execution.setGlobalWorkSize(1);

		Assert.assertEquals(1, execution.getGlobalWorkSize());
		Assert.assertEquals(0, execution.getGlobalWorkOffset());
	}

	/** A range of more than one work item is refused rather than performed as one item. */
	@Test(timeout = 10000)
	public void refusesMoreThanOneWorkItem() {
		Execution execution = execution();

		try {
			execution.setGlobalWorkSize(8);
			Assert.fail("A range of 8 work items must be refused by a single-item executable");
		} catch (UnsupportedOperationException expected) {
			Assert.assertTrue("The refusal should name the work item limit",
					expected.getMessage().contains("single work item"));
		}

		Assert.assertEquals(1, execution.getGlobalWorkSize());
	}

	/** A range starting past the first work item is refused, since no index reaches the executable. */
	@Test(timeout = 10000)
	public void refusesOffsetRange() {
		Execution execution = execution();

		try {
			execution.setGlobalWorkOffset(3);
			Assert.fail("A range starting at work item 3 must be refused by a single-item executable");
		} catch (UnsupportedOperationException expected) {
			Assert.assertTrue("The refusal should name the work item limit",
					expected.getMessage().contains("first work item"));
		}

		Assert.assertEquals(0, execution.getGlobalWorkOffset());
	}

	/**
	 * What the executable leaves in an argument's file is in that argument once the execution
	 * returns, and the data directory it was given is deleted when its results were copied out.
	 * The stand-in executable copies argument 1's values over argument 0's.
	 *
	 * @throws IOException if the stand-in executable cannot be written
	 */
	@Test(timeout = 30000)
	public void copiesResultsOutOfTheExecutable() throws IOException {
		boolean lazy = LocalExternalMemoryProvider.enableLazyReading;
		LocalExternalMemoryProvider.enableLazyReading = false;

		List<File> directories = new ArrayList<>();
		Execution execution = new ExternalInstructionSet(
				executable("cp \"$1/1\" \"$1/0\""), directories(directories)).get("function", 2);

		Bytes out = new Bytes(3);
		Bytes in = new Bytes(3);
		in.getMem().set(0, 1.5, -2.0, 3.25);

		try {
			execution.accept(new Object[] { out, in }, null);

			Assert.assertArrayEquals(new double[] { 1.5, -2.0, 3.25 }, out.toArray(), 0.0);
			Assert.assertTrue("One run should reserve one data directory", directories.size() == 1);
			Assert.assertFalse("The data directory should be deleted once the results are copied out",
					directories.get(0).exists());
		} finally {
			LocalExternalMemoryProvider.enableLazyReading = lazy;
			out.destroy();
			in.destroy();
		}
	}

	/**
	 * With lazy reading the results are read through the files the executable left them in.
	 *
	 * @throws IOException if the stand-in executable cannot be written
	 */
	@Test(timeout = 30000)
	public void mapsResultsOfTheExecutable() throws IOException {
		boolean lazy = LocalExternalMemoryProvider.enableLazyReading;
		LocalExternalMemoryProvider.enableLazyReading = true;

		List<File> directories = new ArrayList<>();
		Execution execution = new ExternalInstructionSet(
				executable("cp \"$1/1\" \"$1/0\""), directories(directories)).get("function", 2);

		Bytes out = new Bytes(2);
		Bytes in = new Bytes(2);
		in.getMem().set(0, 4.0, -0.5);

		try {
			execution.accept(new Object[] { out, in }, null);

			Assert.assertArrayEquals(new double[] { 4.0, -0.5 }, out.toArray(), 0.0);
			Assert.assertArrayEquals(new double[] { 4.0, -0.5 }, in.toArray(), 0.0);
		} finally {
			LocalExternalMemoryProvider.enableLazyReading = lazy;
			out.destroy();
			in.destroy();
		}
	}

	/**
	 * An executable that exits with a failure is reported as one, and the data directory it
	 * was given is still deleted.
	 *
	 * @throws IOException if the stand-in executable cannot be written
	 */
	@Test(timeout = 30000)
	public void reportsAFailingExecutable() throws IOException {
		assertFailedRunDeletesItsDirectory(false, 3);
	}

	/**
	 * A failed run leaves no results to read back, so even with lazy reading, where files that
	 * became memory are kept until the JVM exits, the data directory it was given is deleted.
	 *
	 * @throws IOException if the stand-in executable cannot be written
	 */
	@Test(timeout = 30000)
	public void deletesTheDirectoryOfAFailedLazyRun() throws IOException {
		assertFailedRunDeletesItsDirectory(true, 5);
	}

	/**
	 * Runs an executable that exits with the given failure status, and asserts that the
	 * failure is reported with that status and that the data directory the run was given
	 * has been deleted.
	 *
	 * @param lazyReading whether results are read back lazily during the run
	 * @param status      the exit status of the executable, which must not be 0
	 * @throws IOException if the stand-in executable cannot be written
	 */
	private void assertFailedRunDeletesItsDirectory(boolean lazyReading, int status) throws IOException {
		boolean lazy = LocalExternalMemoryProvider.enableLazyReading;
		LocalExternalMemoryProvider.enableLazyReading = lazyReading;

		List<File> directories = new ArrayList<>();
		Execution execution = new ExternalInstructionSet(
				executable("exit " + status), directories(directories)).get("function", 1);

		Bytes arg = new Bytes(1);

		try {
			execution.accept(new Object[] { arg }, null);
			Assert.fail("An executable exiting with status " + status + " must be reported as a failure");
		} catch (HardwareException expected) {
			Assert.assertTrue("The failure should give the exit status",
					expected.getMessage().contains("(" + status + ")"));
			Assert.assertFalse("The data directory should be deleted after a failed run",
					directories.get(0).exists());
		} finally {
			LocalExternalMemoryProvider.enableLazyReading = lazy;
			arg.destroy();
		}
	}

	/**
	 * Writes a shell script that runs the given command in place of a compiled executable,
	 * which, like one, is given the data directory as its first argument.
	 *
	 * @param command the shell command, which may refer to the data directory as {@code $1}
	 * @return the path of the script
	 * @throws IOException if the script cannot be written or made executable
	 */
	private static String executable(String command) throws IOException {
		File script = File.createTempFile("external-executable", ".sh");
		script.deleteOnExit();
		Files.writeString(script.toPath(), "#!/bin/sh\n" + command + "\n");

		if (!script.setExecutable(true)) {
			throw new IOException("Unable to make " + script + " executable");
		}

		return script.getAbsolutePath();
	}

	/**
	 * Returns a supplier of fresh temporary data directories, recording each one supplied.
	 *
	 * @param supplied the list to record each supplied directory in
	 * @return the supplier
	 */
	private static Supplier<File> directories(List<File> supplied) {
		return () -> {
			try {
				File directory = Files.createTempDirectory("external-data").toFile();
				directory.deleteOnExit();
				supplied.add(directory);
				return directory;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		};
	}
}
