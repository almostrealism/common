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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces the policy that <b>no value computed by Java code may move from the JVM heap
 * into device memory</b>, by requiring that every bulk write surface — {@code setMem(...)}
 * and {@code PackedCollection.of(...)} — is called only with numeric literals.
 *
 * <p>Exactly three kinds of numbers may cross from the JVM into device memory:
 * <ul>
 *   <li>numeric literals written directly in the source, which is convenient
 *       for small constant vectors: {@code setMem(0, 1.0, 2.0)} / {@code setMem(i, 0.0)}
 *       / {@code PackedCollection.of(1.0, 2.0)};</li>
 *   <li>data copied from another {@link org.almostrealism.hardware.MemoryData} — expressed
 *       through the distinctly-named {@code setFrom(...)} surface (or, better, a tracked
 *       {@code cp(src).into(dest).evaluate()} assignment), which never involves host
 *       values at all; and</li>
 *   <li>data entering the JVM from outside the system (deserialization, file and network
 *       I/O), which crosses only through the sanctioned ingest surface listed below.</li>
 * </ul>
 *
 * <p>Every other argument shape is a violation. In particular a host
 * {@code double[]}/{@code float[]} (identifier, {@code new double[...]}, an array index,
 * or a {@code toArray()}/{@code toDouble()}/{@code toFloat()} result) and any computed
 * scalar (a variable, cast, or arithmetic expression) are forbidden: if a value is being
 * <em>computed</em>, it must be produced by a {@link io.almostrealism.relation.Producer}
 * so the computation happens on the device. The question is never where the data
 * originated, but whether Java code computed it; a table of {@code Math.cos} results is
 * computed data no matter how few parameters it derives from, and shipping it in one bulk
 * transfer is the same violation as writing it element by element.
 *
 * <p>Unlike {@link PackedCollectionDetector}, this rule has <b>no exemptions</b>: it applies
 * to test sources as well as main sources, honours no initialization-method or domain
 * whitelist, and cannot be suppressed with a {@code // nopolicy} comment. The only files it
 * skips are the {@linkplain #isSanctionedWriteSurface(Path) sanctioned write surface} — the memory
 * backend that <em>implements</em> the array-accepting overloads and the low-level host&harr;device
 * primitive, together with the collection-population idioms — the sole sanctioned location of a
 * bulk host-array write.
 *
 * <p>The scan is performed on a comment- and string-masked copy of each file, and each
 * {@code .setMem(} call's argument list is extracted with balanced-parenthesis matching, so
 * a call split across lines or containing a commented-out fragment cannot evade detection.
 *
 * @see PolicyViolationDetector
 * @see PackedCollectionDetector
 */
public class SetMemLiteralsDetector extends PolicyViolationDetector {

	/** Rule code reported for a non-literal {@code setMem} argument. */
	public static final String RULE = "SETMEM_NON_LITERAL_ARGUMENT";

	/** Rule code reported for a non-literal {@code PackedCollection.of} argument. */
	public static final String OF_RULE = "PACKED_COLLECTION_OF_NON_LITERAL";

	/** Guidance appended to every violation, naming the sanctioned idioms. */
	private static final String GUIDANCE =
			"setMem writes device memory only from numeric literals, one value at an index "
					+ "(e.g. setMem(i, 1.0)), and that form is declared on MemoryDataAdapter rather "
					+ "than MemoryData so it is reachable only through a concrete implementation. "
					+ "The whole-content form is protected and reachable only from the write surface "
					+ "itself: build a collection from values with PackedCollection.of(...), populate "
					+ "one with fill(...), or load data from outside the system through "
					+ "read(ByteBuffer) / read(InputStream). "
					+ "To copy from another MemoryData use setFrom(...) or cp(src).into(dest).evaluate(); "
					+ "to materialise computed values use a Producer with fill(value) / fill(pos -> ...) "
					+ "or a producer assignment. A host double[]/float[] must never be uploaded via setMem.";

	/** Guidance appended to every {@code PackedCollection.of} violation. */
	private static final String OF_GUIDANCE =
			"PackedCollection.of bulk-copies host values to the device. It is what pack(...) "
					+ "calls, so it is held to the same standard: every argument an individual value "
					+ "(a literal or a scalar expression, never an array, a list, a device read-back, "
					+ "or a call). Values computed in Java must be produced by the computation graph "
					+ "instead (integers(), producer arithmetic, or a producer assignment); data from "
					+ "outside the system enters through the sanctioned ingest surface. Staging "
					+ "computed values in a double[] and shipping them in one transfer is the same "
					+ "violation as writing them element by element.";

	/** Rule code reported for a {@code fill} or {@code pack} call outside the scalar allowance. */
	public static final String INGEST_RULE = "FILL_PACK_BEYOND_SCALAR_ALLOWANCE";

	/** Guidance appended to every {@code fill}/{@code pack} violation. */
	private static final String INGEST_GUIDANCE =
			"fill and pack exist for constant vectors and scalar state writes: every argument "
					+ "must be an individual value (a literal or a scalar expression, never an array, "
					+ "a toArray() result, or a lambda). Literal values are permitted in any number; "
					+ "when any argument is a non-literal scalar there must be fewer than 16 arguments. "
					+ "Anything computed per element must be produced by the computation "
					+ "graph (integers(), producer arithmetic, randn/rand, or a producer assignment).";

	/** Rule code for {@code fill} invoked on a {@code range(...)} view. */
	public static final String RANGE_FILL_RULE = "FILL_ON_RANGE_VIEW";

	/** Guidance attached to {@link #RANGE_FILL_RULE} violations. */
	private static final String RANGE_FILL_GUIDANCE =
			"fill on a range view is setMem(index, value) with different syntax: a scatter write "
					+ "of a host value at a computed position. Address the position inside the "
					+ "computation instead -- use an index-addressed selection as the destination "
					+ "of an Assignment, with the index supplied as data through a provider "
					+ "collection, or produce the whole buffer with a single kernel.";

	/**
	 * File name fragments of the framework's sanctioned write surface: the classes that
	 * implement the array-accepting overloads, the low-level host&harr;device primitive, and the
	 * collection-population idioms this rule redirects authors toward ({@code fill},
	 * {@code replace}, {@code clone}, and the from-host factories on {@code PackedCollection}).
	 * These are the one legitimate home of a bulk host-array write; every other file is subject
	 * to the rule.
	 *
	 * <p>The entries under {@code algebra}, {@code geometry}, {@code color}, and
	 * {@code heredity} are value types: {@code PackedCollection} subclasses (with, for
	 * {@code RGBData192}, the backing store of one) and {@code ScaleFactor}, which holds its
	 * scalar storage by composition. Their setters are the storage-layer write surface of the
	 * type itself, exactly like {@code PackedCollection}'s own population methods; the
	 * migration work for these types is eliminating the <em>call sites</em> that push computed
	 * values through those setters, not rewriting the types' internals.</p>
	 */
	private static final List<String> SANCTIONED_WRITE_SURFACE = List.of(
			"/hardware/MemoryData.java",
			"/hardware/mem/MemoryDataAdapter.java",
			"/code/Memory.java",
			"MemoryProvider.java",           // matches every *MemoryProvider implementation
			"/collect/PackedCollection.java", // implements fill/replace/clone and from-host factories
			"/collect/CollectionCreationFeatures.java", // c(double...) — the host-array to collection ingest primitive
			"/algebra/Pair.java",
			"/algebra/Vector.java",
			"/geometry/Ray.java",
			"/geometry/TransformMatrix.java",
			"/color/RGB.java",
			"/color/RGBData192.java",
			"/heredity/ScaleFactor.java"
	);

	/**
	 * Classpath location of the grandfathered-violation baseline: the inventory of every
	 * violation that already existed when full-tree enforcement was turned on. Each line is
	 * tab-delimited as {@code rule\tpath\tcount\tsource}, where {@code path} is repo-relative
	 * and {@code source} is the trimmed offending line. A scan tolerates at most {@code count}
	 * occurrences of each entry; any occurrence beyond that — and any violation not in the
	 * inventory at all — is reported immediately, in every module. Matching is exact on the
	 * source text, so editing a grandfathered line re-triggers enforcement for it, and the
	 * inventory is the burn-down artifact that migration work shrinks. Regenerate with
	 * {@code java org.almostrealism.util.SetMemLiteralsDetector <rootDir> --generate <file>}.
	 */
	public static final String BASELINE_RESOURCE = "/org/almostrealism/util/setmem-violation-baseline.tsv";

	/**
	 * Burn-down whitelist of individually-acknowledged violations in already-enforced modules that
	 * could not be migrated to a producer/{@code setFrom} idiom. An entry suppresses a single call
	 * only when the file path contains {@code pathFragment} and the offending source line, trimmed,
	 * is exactly {@code sourceLine}, so the entry re-triggers the moment the line is edited. Entries
	 * are writes below the producer API in {@code base/hardware} (which cannot import the collect
	 * layer), the randomness ingest primitive, the mesh-intersection kernel read-back writes in
	 * {@code domain/space}, and the {@code Tensor} bridge for host-resident boxed values (whose
	 * correct long-term treatment is an open question); these are expected to shrink to zero.
	 */
	private static final List<String[]> KNOWN_EXCLUSIONS = List.of(
			new String[] {"/hardware/HardwareFeatures.java", "counter.setMem(0, count);"},
			new String[] {"/hardware/computations/Periodic.java", "counter.setMem(0, count);"},
			new String[] {"/hardware/mem/MemoryDataCacheManager.java", "getData().get(index).setMem(data);"},
			new String[] {"/space/MeshData.java", "destination.setMem(i, result.toDouble(i * 2));"},
			new String[] {"/algebra/Tensor.java", "return PackedCollection.of(values).reshape(shape);"},
			new String[] {"FullAttentionMethodTest.java", "input.setMem(i, pytorchInput[i]);"},
			new String[] {"ResidualBlockSubComponentTest.java", "input.setMem(i, inputData[i]);"},
			new String[] {"ResidualBlockSubComponentTest.java", "input.setMem(i, res0Input[i]);"},
			new String[] {"OobleckLayerValidationTest.java", "input.setMem(i, latentInput[i]);"},
			new String[] {"OobleckValidationTest.java", "input.setMem(i, latentInput[i]);"},
			new String[] {"OobleckValidationTest.java", "input.setMem(i, testInput[i]);"},
			new String[] {"OobleckValidationTest.java", "input.setMem(i, inputConvOutput[i]);"},
			new String[] {"OobleckValidationTest.java", "block2Input.setMem(i, refAfterBlock1[i]);"}
	);

	/** A single numeric literal token: decimal, hex, or float/long-suffixed, with optional sign. */
	private static final Pattern NUMERIC_LITERAL = Pattern.compile(
			"[-+]?(?:0[xX][0-9a-fA-F_]+|(?:\\d[\\d_]*)?\\.?\\d[\\d_]*(?:[eE][-+]?\\d+)?)[fFdDlL]?");

	/**
	 * A {@code new double[] { ... }} or {@code new float[] { ... }} initializer, capturing the
	 * element list. The whole-content write takes an array rather than varargs, so this is the
	 * shape a literal write now arrives in.
	 */
	private static final Pattern LITERAL_ARRAY = Pattern.compile(
			"new\\s+(?:double|float)\\s*\\[\\s*\\]\\s*\\{(.*)\\}", Pattern.DOTALL);

	/** Locates the start of each {@code .setMem(} call. */
	private static final Pattern SETMEM_CALL = Pattern.compile("\\.setMem\\s*\\(");

	/** Locates the start of each {@code PackedCollection.of(} call. */
	private static final Pattern OF_CALL = Pattern.compile("PackedCollection\\s*\\.\\s*of\\s*\\(");

	/**
	 * Locates the start of each {@code .fill(} call, excluding {@link java.util.Arrays}
	 * and {@link java.util.Collections}.
	 *
	 * <p>This rule is about writes into device memory. {@code Arrays.fill} and
	 * {@code Collections.fill} operate on a host array or list and never reach a
	 * {@link org.almostrealism.hardware.MemoryData}, so matching them only fills the
	 * inventory with entries that no migration will ever remove. They are excluded by
	 * name, which would also skip a collection variable whose name ended in
	 * {@code Arrays} or {@code Collections}; no such name exists, and one would be a
	 * poor name for a collection.</p>
	 */
	private static final Pattern FILL_CALL =
			Pattern.compile("(?<!Arrays)(?<!Collections)\\.fill\\s*\\(");

	/**
	 * Locates each {@code fill} call invoked directly on a {@code range(...)} view. A
	 * single-element range view makes {@code fill} into {@code setMem(index, value)} with
	 * different syntax — a scatter write of a host value at a computed position — so the
	 * combination is never permitted, whatever the arguments.
	 */
	private static final Pattern RANGE_FILL_CALL = Pattern.compile(
			"\\.range\\s*\\((?:[^()]|\\([^()]*\\))*\\)\\s*\\.fill\\s*\\(");

	/** Locates the start of each unqualified {@code pack(} call. */
	private static final Pattern PACK_CALL = Pattern.compile("(?<![\\w.$])pack\\s*\\(");

	/** The maximum number of individual scalar arguments a {@code fill}/{@code pack} call may pass. */
	private static final int SCALAR_ALLOWANCE = 16;

	/** A bare Java identifier (used to recognise a lone offset/source argument). */
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

	/**
	 * Remaining tolerated occurrences of each grandfathered violation, keyed by
	 * {@code path\0source} and decremented as matching occurrences are found
	 * during the scan. Loaded from {@link #BASELINE_RESOURCE}; empty when the
	 * baseline is disabled or absent.
	 */
	private final Map<String, Integer> baseline;

	/**
	 * The tolerated occurrence count of each baseline entry before any were consumed by
	 * the scan, so {@link #exemptionSummary()} can report how many grandfathered
	 * occurrences are still present in source versus how many ledger rows are stale.
	 */
	private final Map<String, Integer> baselineInitial;

	/**
	 * The {@link #KNOWN_EXCLUSIONS} entries actually encountered during the scan, keyed
	 * by {@code pathFragment\0sourceLine}; entries never encountered are candidates for
	 * removal from the exclusion list.
	 */
	private final Set<String> exclusionsMatched = new HashSet<>();

	/**
	 * Creates a detector that will scan Java source files under the given directory,
	 * tolerating the violations grandfathered in {@link #BASELINE_RESOURCE}.
	 *
	 * @param rootDir  the root directory to scan
	 */
	public SetMemLiteralsDetector(Path rootDir) {
		this(rootDir, true);
	}

	/**
	 * Creates a detector that will scan Java source files under the given directory.
	 *
	 * @param rootDir      the root directory to scan
	 * @param useBaseline  whether to tolerate the violations grandfathered in
	 *                     {@link #BASELINE_RESOURCE}; disabled when generating a
	 *                     fresh baseline
	 */
	public SetMemLiteralsDetector(Path rootDir, boolean useBaseline) {
		super(rootDir);
		this.baseline = useBaseline ? loadBaseline() : new HashMap<>();
		this.baselineInitial = new HashMap<>(baseline);
	}

	/**
	 * Loads the grandfathered-violation inventory from {@link #BASELINE_RESOURCE}.
	 *
	 * @return remaining tolerated occurrences keyed by {@code path\0source};
	 *         empty when the resource is absent
	 */
	private static Map<String, Integer> loadBaseline() {
		Map<String, Integer> entries = new HashMap<>();

		try (InputStream in = SetMemLiteralsDetector.class.getResourceAsStream(BASELINE_RESOURCE)) {
			if (in == null) return entries;

			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				String[] parts = line.split("\t", 4);
				if (parts.length != 4) continue;
				entries.merge(parts[1] + '\0' + parts[3], Integer.parseInt(parts[2]), Integer::sum);
			}
		} catch (IOException e) {
			throw new RuntimeException("Could not read " + BASELINE_RESOURCE, e);
		}

		return entries;
	}

	/**
	 * Consumes one tolerated occurrence of the given violation from the grandfathered
	 * baseline, if any budget remains for its file and source line.
	 *
	 * @param file  the file containing the violation
	 * @param line  the trimmed source line of the violation
	 * @return      {@code true} if the occurrence was grandfathered and should not be reported
	 */
	private boolean consumeBaseline(Path file, String line) {
		String path = file.toString().replace('\\', '/');

		for (Map.Entry<String, Integer> entry : baseline.entrySet()) {
			if (entry.getValue() <= 0) continue;

			int split = entry.getKey().indexOf('\0');
			String entryPath = entry.getKey().substring(0, split);
			String entryLine = entry.getKey().substring(split + 1);

			if (line.equals(entryLine) && (path.endsWith("/" + entryPath) || path.equals(entryPath))) {
				entry.setValue(entry.getValue() - 1);
				return true;
			}
		}

		return false;
	}

	/**
	 * Scans a single file for non-literal {@code setMem} and {@code PackedCollection.of}
	 * argument usage.
	 *
	 * @param file  the file to scan
	 * @return this detector for chaining
	 */
	@Override
	public SetMemLiteralsDetector scanFile(Path file) {
		if (isExcluded(file) || isSanctionedWriteSurface(file)) return this;

		try {
			String content = Files.readString(file);
			if (!content.contains(".setMem(") && !content.contains(".of(")
					&& !content.contains(".fill(") && !content.contains("pack(")) {
				return this;
			}

			String masked = maskCommentsAndStrings(content);
			scanCalls(file, content, masked, SETMEM_CALL,
					args -> isSanctioned(args, masked), RULE, GUIDANCE);
			scanCalls(file, content, masked, OF_CALL,
					args -> isWithinScalarAllowance(args, masked), OF_RULE, OF_GUIDANCE);
			scanCalls(file, content, masked, RANGE_FILL_CALL,
					args -> false, RANGE_FILL_RULE, RANGE_FILL_GUIDANCE);
			scanCalls(file, content, masked, FILL_CALL,
					args -> isWithinScalarAllowance(args, masked), INGEST_RULE, INGEST_GUIDANCE);
			scanCalls(file, content, masked, PACK_CALL,
					args -> isWithinScalarAllowance(args, masked), INGEST_RULE, INGEST_GUIDANCE);
		} catch (IOException e) {
			warn("Could not read file " + file, e);
		}

		return this;
	}

	/**
	 * Scans the given directory for violations and either reports them or emits a fresh
	 * grandfathered-violation baseline.
	 *
	 * <p>With only a root directory argument, runs the scan (honouring the current baseline)
	 * and exits with status 1 when any unbaselined violation is found. With
	 * {@code --generate <file>}, scans with the baseline disabled and writes the resulting
	 * inventory to the given file in {@link #BASELINE_RESOURCE} format.</p>
	 *
	 * @param args  the root directory, optionally followed by {@code --generate} and an output file
	 * @throws IOException if the scan or the baseline write fails
	 */
	public static void main(String[] args) throws IOException {
		Path root = Path.of(args[0]);
		boolean generate = args.length > 2 && "--generate".equals(args[1]);

		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(root, !generate);
		detector.scan();

		if (generate) {
			Map<String, Integer> counts = new TreeMap<>();
			for (Violation v : detector.getViolations()) {
				String path = root.toAbsolutePath().relativize(
						v.getFile().toAbsolutePath()).toString().replace('\\', '/');
				counts.merge(v.getRule() + '\t' + path + '\0' + v.getLine().trim(), 1, Integer::sum);
			}

			StringBuilder out = new StringBuilder();
			for (Map.Entry<String, Integer> entry : counts.entrySet()) {
				int split = entry.getKey().indexOf('\0');
				out.append(entry.getKey(), 0, split).append('\t')
						.append(entry.getValue()).append('\t')
						.append(entry.getKey().substring(split + 1)).append('\n');
			}

			Files.writeString(Path.of(args[2]), out.toString());
		} else {
			detector.log(detector.generateReport());
			detector.log(detector.exemptionSummary());
			if (detector.hasViolations()) System.exit(1);
		}
	}

	/**
	 * Scans the masked file content for every call matched by {@code call}, reporting a
	 * violation with the given rule and guidance for each argument list that the sanction
	 * test rejects and that is neither an acknowledged {@link #KNOWN_EXCLUSIONS} entry nor
	 * a remaining occurrence of a {@linkplain #BASELINE_RESOURCE grandfathered} violation.
	 *
	 * @param file      the file being scanned
	 * @param content   the raw file content, used for line numbers and display text
	 * @param masked    the comment- and string-masked content, used for matching
	 * @param call      the pattern locating the start of each call's argument list
	 * @param sanction  the test a call's argument text must pass to be permitted
	 * @param rule      the rule code to report for rejected calls
	 * @param guidance  the guidance to attach to reported violations
	 */
	private void scanCalls(Path file, String content, String masked, Pattern call,
						   Predicate<String> sanction, String rule, String guidance) {
		Matcher m = call.matcher(masked);
		while (m.find()) {
			int argsStart = m.end();
			int argsEnd = matchingParen(masked, argsStart);
			if (argsEnd < 0) continue;

			String argString = masked.substring(argsStart, argsEnd);
			if (!sanction.test(argString)) {
				int lineNum = countLines(content, m.start());
				String line = lineText(content, lineNum);
				if (isKnownExclusion(file, line) || consumeBaseline(file, line)) continue;
				violations.add(new Violation(file, lineNum, line, rule, guidance));
			}
		}
	}


	/**
	 * Determines whether a {@code fill}/{@code pack} argument list is within the scalar
	 * allowance: every argument must be an individual scalar value — never an array, a
	 * device read-back, a lambda, a method reference, or a call (whose result could be an
	 * array the scan cannot see). Literal values are permitted in any number, matching the
	 * literal varargs {@code setMem} surface; when any argument is a non-literal scalar,
	 * there must be fewer than {@link #SCALAR_ALLOWANCE} arguments in total. A computed
	 * scalar is passed by hoisting it to a local first; a local declared as an array is
	 * recognised and rejected. A zero-argument call transfers nothing and is permitted
	 * (this also covers method declarations such as {@code Tensor.pack()}).
	 *
	 * @param argString  the raw text between the call's parentheses (comment/string masked)
	 * @param masked     the whole masked file, used to resolve an identifier's declared type
	 * @return           {@code true} if the call is within the allowance
	 */
	private boolean isWithinScalarAllowance(String argString, String masked) {
		List<String> args = splitTopLevel(argString);
		if (args.isEmpty()) return true;

		boolean allLiterals = true;

		for (String arg : args) {
			if (isArrayish(arg) || containsCall(arg) || arg.contains("->") || arg.contains("::")) {
				return false;
			}

			String trimmed = arg.trim();
			if (IDENTIFIER.matcher(trimmed).matches() && isDeclaredArray(masked, trimmed)) {
				return false;
			}

			if (!isNumericLiteral(trimmed)) allLiterals = false;
		}

		return allLiterals || args.size() < SCALAR_ALLOWANCE;
	}

	/**
	 * Returns {@code true} if the file is part of the framework's sanctioned write surface that
	 * legitimately implements the array-accepting overloads, the low-level host&harr;device
	 * primitive, or the {@code fill()}/{@code replace()}/{@code clone()} population idioms.
	 *
	 * @param file  the file to test
	 * @return      whether the file is exempt as sanctioned write-surface implementation
	 */
	private boolean isSanctionedWriteSurface(Path file) {
		String path = file.toString().replace('\\', '/');
		for (String fragment : SANCTIONED_WRITE_SURFACE) {
			if (path.contains(fragment)) return true;
		}
		return false;
	}

	/**
	 * Returns {@code true} if the offending call is an individually-acknowledged entry on the
	 * {@link #KNOWN_EXCLUSIONS} burn-down list — the file path contains the entry's path fragment
	 * and the trimmed source line matches exactly.
	 *
	 * @param file  the file containing the call
	 * @param line  the trimmed source line of the {@code setMem} call
	 * @return      whether this specific call is a known, temporarily-excluded violation
	 */
	private boolean isKnownExclusion(Path file, String line) {
		String path = file.toString().replace('\\', '/');
		for (String[] entry : KNOWN_EXCLUSIONS) {
			if (path.contains(entry[0]) && line.equals(entry[1])) {
				exclusionsMatched.add(entry[0] + '\0' + entry[1]);
				return true;
			}
		}
		return false;
	}

	/**
	 * Summarizes the exemptions that remain after a scan: how many grandfathered
	 * occurrences are still present in source, how many baseline ledger rows no longer
	 * match any code (fully migrated, awaiting removal from the inventory), and how many
	 * of the acknowledged {@link #KNOWN_EXCLUSIONS} were actually encountered.
	 *
	 * <p>These numbers are the burn-down metric for the migration effort: the goal is to
	 * drive the live counts to zero, at which point the baseline resource and the
	 * exclusion list can both be deleted. Only meaningful after {@code scan()} on a run
	 * constructed with the baseline enabled.</p>
	 *
	 * @return a multi-line summary of remaining exemptions
	 */
	public String exemptionSummary() {
		int initialOccurrences = 0;
		int remainingBudget = 0;
		int liveEntries = 0;
		int staleEntries = 0;

		for (Map.Entry<String, Integer> entry : baseline.entrySet()) {
			int initial = baselineInitial.getOrDefault(entry.getKey(), 0);
			initialOccurrences += initial;
			remainingBudget += entry.getValue();
			if (entry.getValue() < initial) {
				liveEntries++;
			} else {
				staleEntries++;
			}
		}

		int liveOccurrences = initialOccurrences - remainingBudget;

		return "Exemptions remaining: " + liveOccurrences + " grandfathered occurrences across " +
				liveEntries + " baseline entries still present in source\n" +
				"  (inventory holds " + baseline.size() + " entries tolerating " + initialOccurrences +
				" occurrences; " + staleEntries + " entries no longer match any code and can be removed)\n" +
				"  plus " + exclusionsMatched.size() + " of " + KNOWN_EXCLUSIONS.size() +
				" acknowledged burn-down exclusions encountered in source";
	}

	/**
	 * Determines whether a {@code setMem} argument list is one of the sanctioned shapes:
	 * numeric literals written from index 0 ({@code setMem(double...)}), or an index
	 * expression followed by exactly one numeric literal ({@code setMem(int, double)}).
	 * Any array syntax, or an array-typed leading argument, makes the call a violation.
	 *
	 * <p>An index expression followed by several values is rejected because that overload
	 * no longer exists. Such a call is not a compile error — it binds to the whole-content
	 * varargs form, which writes the index itself as data at offset 0 — so the shape has to
	 * be caught here, where it can be reported, rather than resolving differently than it
	 * reads. A leading numeric literal is indistinguishable from a first value, so an
	 * all-literal list is accepted whichever of the two shapes the author intended.</p>
	 *
	 * <p>A call passing five arguments is the low-level host&harr;device primitive —
	 * {@code MemoryProvider.setMem(mem, offset, source, srcOffset, length)} or the static
	 * {@code MemoryData.setMem(Memory, ...)} it delegates to — and is not subject to this
	 * rule, which governs writes into a {@link org.almostrealism.hardware.MemoryData}. No
	 * instance overload takes five arguments; the widest takes two. If one is ever added,
	 * this allowance has to be revisited, because arity is what distinguishes them.</p>
	 *
	 * @param argString  the raw text between the call's parentheses (comment/string masked)
	 * @param masked     the whole masked file, used to resolve a leading identifier's type
	 * @return           {@code true} if the call is sanctioned
	 */
	private boolean isSanctioned(String argString, String masked) {
		if (splitTopLevel(argString).size() == 5) {
			return true;
		}

		List<String> args = splitTopLevel(argString);
		if (args.isEmpty()) return false;

		if (args.size() == 1 && isLiteralArrayInitializer(args.get(0))) {
			return true;
		}

		// Any other array construction, indexing, or device->host read is forbidden.
		for (String arg : args) {
			if (isArrayish(arg)) return false;
		}

		if (args.size() == 1) {
			return isNumericLiteral(args.get(0));
		}

		// With two or more arguments the first is the destination offset (an index, which
		// may be a variable or expression) UNLESS it is a bare host-array identifier — the
		// setMem(double[] source, int srcOffset) shape — which is a forbidden array upload.
		String first = args.get(0).trim();
		if (IDENTIFIER.matcher(first).matches() && isDeclaredArray(masked, first)) {
			return false;
		}

		if (!isNumericLiteral(first) && args.size() > 2) {
			return false;
		}

		for (int i = 1; i < args.size(); i++) {
			if (!isNumericLiteral(args.get(i))) return false;
		}
		return true;
	}

	/**
	 * Returns {@code true} if the argument is exactly a numeric literal token.
	 *
	 * @param arg  the argument text
	 * @return     whether it is a single numeric literal
	 */
	private boolean isNumericLiteral(String arg) {
		return NUMERIC_LITERAL.matcher(arg.trim()).matches();
	}

	/**
	 * Returns {@code true} if the argument is an array initializer whose every element is a
	 * numeric literal — {@code new double[] { 1.0, 2.0 }}.
	 *
	 * <p>The whole-content write is declared over an array rather than varargs, because a
	 * varargs form silently swallows an indexed write issued against a {@code MemoryData}
	 * reference. A literal write therefore arrives as an initializer, and is held to the same
	 * standard the varargs list was: every element a literal, no computed values, no host
	 * array reaching the device under another name.</p>
	 *
	 * @param arg  the argument text
	 * @return     whether it is an all-literal array initializer
	 */
	private boolean isLiteralArrayInitializer(String arg) {
		Matcher matcher = LITERAL_ARRAY.matcher(arg.trim());
		if (!matcher.matches()) return false;

		String body = matcher.group(1).trim();
		if (body.isEmpty()) return false;

		for (String element : splitTopLevel(body)) {
			if (!isNumericLiteral(element)) return false;
		}

		return true;
	}

	/**
	 * Returns {@code true} if the argument constructs, indexes, or reads back a host array —
	 * {@code new T[...]}, an {@code ident[...]} index, or a
	 * {@code toArray()}/{@code toDouble()}/{@code toFloat()}/{@code toFloatArray()} result.
	 *
	 * @param arg  the argument text
	 * @return     whether the argument involves a host array
	 */
	private boolean isArrayish(String arg) {
		if (arg.contains("new ") && arg.contains("[")) return true;
		if (arg.contains("[") && arg.contains("]")) return true;
		return arg.contains(".toArray(") || arg.contains(".toDouble(")
				|| arg.contains(".toFloat(") || arg.contains(".toFloatArray(");
	}

	/**
	 * Returns {@code true} if the argument invokes a method — an opening parenthesis directly
	 * preceded by an identifier character.
	 *
	 * <p>A call is rejected because the scan cannot see what it returns, and an array would
	 * be indistinguishable from a scalar. Parentheses that merely group, as in a cast or an
	 * arithmetic expression, carry no such uncertainty: whatever they contain is still
	 * examined, so an array reaching the device through one is caught on its own terms.</p>
	 *
	 * @param arg  the argument text
	 * @return     whether the argument contains a method invocation
	 */
	private boolean containsCall(String arg) {
		for (int i = arg.indexOf('('); i >= 0; i = arg.indexOf('(', i + 1)) {
			int p = i - 1;
			while (p >= 0 && arg.charAt(p) == ' ') p--;
			if (p >= 0 && (Character.isJavaIdentifierPart(arg.charAt(p)))) return true;
		}

		return false;
	}

	/**
	 * Returns {@code true} if {@code ident} is declared anywhere in the (masked) file with a
	 * type that carries many values rather than one — an array or varargs ({@code T[] ident},
	 * {@code T... ident}) or a collection of boxed numbers ({@code List<Double> ident}).
	 *
	 * <p>The collection case matters because the ingest surface is overloaded for it:
	 * {@code PackedCollection.of(List<Double>)} moves as many values as the list holds, so an
	 * identifier of that type is a bulk source even though it carries no array syntax.</p>
	 *
	 * @param masked  the comment/string-masked file content
	 * @param ident   the identifier to look up
	 * @return        whether the identifier names many values rather than one
	 */
	private boolean isDeclaredArray(String masked, String ident) {
		Pattern decl = Pattern.compile(
				"[A-Za-z_$][\\w$.]*(?:\\s*<[^;{}=]*>)?\\s*(?:\\[\\s*\\]|\\.\\.\\.)\\s+"
						+ Pattern.quote(ident) + "\\b");
		if (decl.matcher(masked).find()) return true;

		Pattern collection = Pattern.compile(
				"(?:List|Collection|Iterable|Set|Queue|Deque|ArrayList|LinkedList)"
						+ "\\s*<[^;{}=]*>\\s+" + Pattern.quote(ident) + "\\b");
		return collection.matcher(masked).find();
	}

	/**
	 * Returns the index of the {@code )} that closes the {@code (} preceding {@code start},
	 * accounting for nested parentheses. Assumes the opening parenthesis has already been
	 * consumed (so {@code start} is the first character of the argument list).
	 *
	 * @param text   the text to scan
	 * @param start  index of the first character after the opening {@code (}
	 * @return       index of the matching {@code )}, or {@code -1} if unbalanced
	 */
	private int matchingParen(String text, int start) {
		int depth = 1;
		for (int i = start; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '(') depth++;
			else if (c == ')') {
				depth--;
				if (depth == 0) return i;
			}
		}
		return -1;
	}

	/**
	 * Splits an argument list on top-level commas (ignoring commas nested inside parentheses,
	 * brackets, or braces).
	 *
	 * @param argString  the argument list text
	 * @return           the trimmed top-level arguments
	 */
	private List<String> splitTopLevel(String argString) {
		List<String> args = new ArrayList<>();
		int depth = 0;
		StringBuilder cur = new StringBuilder();
		for (int i = 0; i < argString.length(); i++) {
			char c = argString.charAt(i);
			if (c == '(' || c == '[' || c == '{') depth++;
			else if (c == ')' || c == ']' || c == '}') depth--;

			if (c == ',' && depth == 0) {
				add(args, cur);
				cur.setLength(0);
			} else {
				cur.append(c);
			}
		}
		add(args, cur);
		return args;
	}

	/**
	 * Appends the trimmed contents of {@code cur} to {@code args} when non-empty.
	 *
	 * @param args  the accumulating argument list
	 * @param cur   the current argument buffer
	 */
	private void add(List<String> args, StringBuilder cur) {
		String trimmed = cur.toString().trim();
		if (!trimmed.isEmpty()) args.add(trimmed);
	}

	/**
	 * Returns a copy of {@code text} with the contents of line, block, and Javadoc comments and
	 * of string/char literals replaced by spaces, preserving length and newlines so that
	 * character offsets and line numbers are unchanged.
	 *
	 * @param text  the source text
	 * @return      the masked text
	 */
	private String maskCommentsAndStrings(String text) {
		char[] out = text.toCharArray();
		int n = text.length();
		int i = 0;
		while (i < n) {
			char c = text.charAt(i);
			if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
				while (i < n && text.charAt(i) != '\n') out[i++] = ' ';
			} else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
				out[i++] = ' ';
				if (i < n) out[i++] = ' ';
				while (i < n && !(text.charAt(i) == '*' && i + 1 < n && text.charAt(i + 1) == '/')) {
					if (text.charAt(i) != '\n') out[i] = ' ';
					i++;
				}
				if (i < n) out[i++] = ' ';
				if (i < n) out[i++] = ' ';
			} else if (c == '"' || c == '\'') {
				char quote = c;
				i++;
				while (i < n && text.charAt(i) != quote) {
					if (text.charAt(i) == '\\' && i + 1 < n) {
						out[i++] = ' ';
						out[i++] = ' ';
						continue;
					}
					if (text.charAt(i) != '\n') out[i] = ' ';
					i++;
				}
				if (i < n) i++;
			} else {
				i++;
			}
		}
		return new String(out);
	}

	/**
	 * Returns the 1-based {@code lineNum} line of {@code content}, trimmed, for violation display.
	 *
	 * @param content  the file content
	 * @param lineNum  the 1-based line number
	 * @return         the trimmed line, or an empty string if out of range
	 */
	private String lineText(String content, int lineNum) {
		String[] lines = content.split("\n", -1);
		return lineNum >= 1 && lineNum <= lines.length ? lines[lineNum - 1].trim() : "";
	}
}
