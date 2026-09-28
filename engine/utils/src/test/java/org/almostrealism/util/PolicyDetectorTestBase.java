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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared fixture for the policy-detector tests. The detectors read source
 * files from disk, so their tests need a scratch directory into which known
 * source content can be written before {@code scanFile}/{@code scan} is
 * invoked. Centralizing that fixture here keeps every detector test writing
 * files the same way.
 */
public abstract class PolicyDetectorTestBase extends TestSuiteBase {

	/**
	 * Writes {@code content} to a file named {@code fileName} inside a fresh
	 * temporary directory and returns the file path. The directory and file are
	 * scheduled for deletion when the JVM exits.
	 *
	 * @param fileName the name (with extension) of the file to create
	 * @param content  the file body
	 * @return the path to the written file
	 * @throws IOException if the temporary directory or file cannot be created
	 */
	protected Path writeSource(String fileName, String content) throws IOException {
		Path dir = Files.createTempDirectory("detector");
		dir.toFile().deleteOnExit();
		Path file = dir.resolve(fileName);
		Files.writeString(file, content);
		file.toFile().deleteOnExit();
		return file;
	}

	/**
	 * Writes {@code content} to a {@code Sample.java} file inside a fresh
	 * temporary directory.
	 *
	 * @param content the file body
	 * @return the path to the written {@code Sample.java}
	 * @throws IOException if the temporary directory or file cannot be created
	 */
	protected Path javaSource(String content) throws IOException {
		return writeSource("Sample.java", content);
	}

	/**
	 * Writes {@code content} to a file at {@code relativePath} beneath a fresh
	 * temporary directory, creating any intermediate directories. Some
	 * detectors (for example {@link ProducerPatternDetector}) key their checks
	 * off substrings of the file's path, so the caller supplies a relative path
	 * that reproduces the relevant tree fragment.
	 *
	 * @param relativePath the path (with separators) beneath the temporary root
	 * @param content      the file body
	 * @return the path to the written file
	 * @throws IOException if the directories or file cannot be created
	 */
	protected Path writeSourceAt(String relativePath, String content) throws IOException {
		Path root = Files.createTempDirectory("detector");
		root.toFile().deleteOnExit();
		Path file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return file;
	}

	/**
	 * Creates a fresh temporary directory into which several related source
	 * files can be written for detectors whose {@code scan()} walks a tree.
	 *
	 * @return the path to the created directory
	 * @throws IOException if the directory cannot be created
	 */
	protected Path sourceDir() throws IOException {
		Path dir = Files.createTempDirectory("detector");
		dir.toFile().deleteOnExit();
		return dir;
	}

	/**
	 * Writes {@code content} to a file named {@code fileName} at the given
	 * (possibly nested) location beneath {@code dir}, creating intermediate
	 * directories as needed.
	 *
	 * @param dir      the directory to write beneath
	 * @param fileName the file name (may contain path separators)
	 * @param content  the file body
	 * @return the path to the written file
	 * @throws IOException if the file cannot be created
	 */
	protected Path write(Path dir, String fileName, String content) throws IOException {
		Path file = dir.resolve(fileName);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return file;
	}
}
