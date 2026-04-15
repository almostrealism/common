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

package org.almostrealism.util;

import org.almostrealism.io.Console;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Composite code policy enforcement tool that delegates to specialized detectors.
 *
 * <p>This class aggregates violations from all policy detector categories:
 * <ul>
 *   <li>{@link PackedCollectionDetector} — CPU loops, arraycopy, and round-trip patterns
 *       that defeat GPU parallelism</li>
 *   <li>{@link ProducerPatternDetector} — {@code .evaluate()} and {@code .toDouble()}
 *       calls inside computation source trees</li>
 *   <li>{@link NamingConventionDetector} — {@code *Cell}/{@code *Block} interface contracts
 *       and {@code *Features} abstract-method violations</li>
 * </ul>
 *
 * <p><b>This tool exists because documentation alone does not prevent violations.</b>
 * The build must fail when violations are detected.
 *
 * <h2>Detected Violations</h2>
 * <ul>
 *   <li>CPU loops with setMem() — defeats GPU parallelism</li>
 *   <li>CPU loops with toDouble()/toArray() followed by setMem() — forces GPU-CPU round trips</li>
 *   <li>System.arraycopy near PackedCollection — cannot move GPU data</li>
 *   <li>Interfaces named *Features with abstract methods — violates Features pattern convention</li>
 *   <li>.evaluate() calls in computation code (engine/ml/, studio/ source trees)</li>
 *   <li>.toDouble() calls in computation code</li>
 *   <li>Classes named *Cell that don't implement org.almostrealism.graph.Cell</li>
 *   <li>Classes named *Block that don't implement org.almostrealism.model.Block</li>
 * </ul>
 *
 * @see PackedCollectionDetector
 * @see ProducerPatternDetector
 * @see NamingConventionDetector
 * @see PolicyViolationDetector
 */
public class CodePolicyViolationDetector extends PolicyViolationDetector {

	/**
	 * Creates a detector that will scan Java source files under the given directory.
	 *
	 * @param rootDir  the root directory to scan
	 */
	public CodePolicyViolationDetector(Path rootDir) {
		super(rootDir);
	}

	/**
	 * Scans all Java source files under the root directory for all violation categories.
	 *
	 * <p>Delegates to {@link PackedCollectionDetector}, {@link ProducerPatternDetector},
	 * and {@link NamingConventionDetector}, then aggregates their results.
	 *
	 * @return this detector for chaining
	 * @throws IOException if file reading fails
	 */
	@Override
	public CodePolicyViolationDetector scan() throws IOException {
		violations.clear();

		PackedCollectionDetector packed = new PackedCollectionDetector(rootDir);
		packed.scan();
		violations.addAll(packed.getViolations());

		ProducerPatternDetector producer = new ProducerPatternDetector(rootDir);
		producer.scan();
		violations.addAll(producer.getViolations());

		NamingConventionDetector naming = new NamingConventionDetector(rootDir);
		naming.scan();
		violations.addAll(naming.getViolations());

		return this;
	}

	/**
	 * Scans a single file for all violation categories.
	 *
	 * <p>Delegates to each sub-detector and aggregates their results.
	 *
	 * @param file  the file to scan
	 * @return this detector for chaining
	 */
	@Override
	public CodePolicyViolationDetector scanFile(Path file) {
		PackedCollectionDetector packed = new PackedCollectionDetector(rootDir);
		packed.scanFile(file);
		violations.addAll(packed.getViolations());

		ProducerPatternDetector producer = new ProducerPatternDetector(rootDir);
		producer.scanFile(file);
		violations.addAll(producer.getViolations());

		NamingConventionDetector naming = new NamingConventionDetector(rootDir);
		naming.scanFile(file);
		violations.addAll(naming.getViolations());

		return this;
	}

	/**
	 * Command-line entry point for manual scanning.
	 *
	 * @param args  optional: path to root directory (defaults to current directory)
	 * @throws IOException if file reading fails
	 */
	public static void main(String[] args) throws IOException {
		Path rootDir = args.length > 0 ? Path.of(args[0]) : Path.of(".");

		CodePolicyViolationDetector detector = new CodePolicyViolationDetector(rootDir);
		detector.scan();

		Console.root().println(detector.generateReport());

		if (detector.hasViolations()) {
			System.exit(1);
		}
	}
}
