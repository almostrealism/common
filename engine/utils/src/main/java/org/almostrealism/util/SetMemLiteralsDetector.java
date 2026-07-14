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
import java.util.List;
import java.util.Map;
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
			"setMem writes device memory only from numeric literals (e.g. setMem(0, 1.0, 2.0)). "
					+ "To copy from another MemoryData use setFrom(...) or cp(src).into(dest).evaluate(); "
					+ "to materialise computed values use a Producer with fill(value) / fill(pos -> ...) "
					+ "or a producer assignment. A host double[]/float[] must never be uploaded via setMem.";

	/** Guidance appended to every {@code PackedCollection.of} violation. */
	private static final String OF_GUIDANCE =
			"PackedCollection.of bulk-copies host values to the device and accepts only numeric "
					+ "literals (e.g. PackedCollection.of(1.0, 2.0)). Values computed in Java must be "
					+ "produced by the computation graph instead (integers(), producer arithmetic, or a "
					+ "producer assignment); data from outside the system enters through the sanctioned "
					+ "ingest surface. Staging computed values in a double[] and shipping them in one "
					+ "transfer is the same violation as writing them element by element.";

	/** Rule code reported for a {@code fill} or {@code pack} call outside the scalar allowance. */
	public static final String INGEST_RULE = "FILL_PACK_BEYOND_SCALAR_ALLOWANCE";

	/** Guidance appended to every {@code fill}/{@code pack} violation. */
	private static final String INGEST_GUIDANCE =
			"fill and pack exist for small constant vectors and scalar state writes: every argument "
					+ "must be an individual value (a literal or a scalar expression, never an array, "
					+ "a toArray() result, or a lambda) and there must be fewer than 16 of them. "
					+ "Anything larger or computed per element must be produced by the computation "
					+ "graph (integers(), producer arithmetic, randn/rand, or a producer assignment).";

	/**
	 * File name fragments of the framework's sanctioned write surface: the classes that
	 * implement the array-accepting overloads, the low-level host&harr;device primitive, and the
	 * collection-population idioms this rule redirects authors toward ({@code fill},
	 * {@code replace}, {@code clone}, and the from-host factories on {@code PackedCollection}).
	 * These are the one legitimate home of a bulk host-array write; every other file is subject
	 * to the rule.
	 *
	 * <p>The entries under {@code algebra}, {@code geometry}, and {@code color} are
	 * {@code PackedCollection} value types (and, for {@code RGBData192}, the backing store of
	 * one). Their setters are the storage-layer write surface of the type itself, exactly like
	 * {@code PackedCollection}'s own population methods; the migration work for these types is
	 * eliminating the <em>call sites</em> that push computed values through those setters, not
	 * rewriting the types' internals.</p>
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
			"/color/RGBData192.java"
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
	 * layer), the randomness ingest primitive, and the {@code Tensor} bridge for host-resident boxed
	 * values (whose correct long-term treatment is an open question); these are expected to shrink
	 * to zero.
	 */
	private static final List<String[]> KNOWN_EXCLUSIONS = List.of(
			new String[] {"/hardware/HardwareFeatures.java", "counter.setMem(0, count);"},
			new String[] {"/hardware/computations/Periodic.java", "counter.setMem(0, count);"},
			new String[] {"/hardware/mem/MemoryDataCacheManager.java", "getData().setMem(entrySize * index, data);"},
			new String[] {"/collect/computations/Random.java", "((MemoryBank) destination).setMem(values);"},
			new String[] {"/algebra/Tensor.java", "return PackedCollection.of(values).reshape(shape);"}
	);

	/** A single numeric literal token: decimal, hex, or float/long-suffixed, with optional sign. */
	private static final Pattern NUMERIC_LITERAL = Pattern.compile(
			"[-+]?(?:0[xX][0-9a-fA-F_]+|(?:\\d[\\d_]*)?\\.?\\d[\\d_]*(?:[eE][-+]?\\d+)?)[fFdDlL]?");

	/** Locates the start of each {@code .setMem(} call. */
	private static final Pattern SETMEM_CALL = Pattern.compile("\\.setMem\\s*\\(");

	/** Locates the start of each {@code PackedCollection.of(} call. */
	private static final Pattern OF_CALL = Pattern.compile("PackedCollection\\s*\\.\\s*of\\s*\\(");

	/** Locates the start of each {@code .fill(} call. */
	private static final Pattern FILL_CALL = Pattern.compile("\\.fill\\s*\\(");

	/** Locates the start of each unqualified {@code pack(} call. */
	private static final Pattern PACK_CALL = Pattern.compile("(?<![\\w.$])pack\\s*\\(");

	/** The maximum number of individual scalar arguments a {@code fill}/{@code pack} call may pass. */
	private static final int SCALAR_ALLOWANCE = 16;

	/** A bare Java identifier (used to recognise a lone offset/source argument). */
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

	/**
	 * Remaining tolerated occurrences of each grandfathered violation, keyed by
	 * {@code path source} and decremented as matching occurrences are found
	 * during the scan. Loaded from {@link #BASELINE_RESOURCE}; empty when the
	 * baseline is disabled or absent.
	 */
	private final Map<String, Integer> baseline;

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
	}

	/**
	 * Loads the grandfathered-violation inventory from {@link #BASELINE_RESOURCE}.
	 *
	 * @return remaining tolerated occurrences keyed by {@code path source};
	 *         empty when the resource is absent
	 */
	private static Map<String, Integer> loadBaseline() {
		Map<String, Integer> entries = new HashMap<>();

		try (InputStream in = SetMemLiteralsDetector.class.getResourceAsStream(BASELINE_RESOURCE)) {
			if (in == null) return entries;

			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				String[] parts = line.split("\t", 4);
				if (parts.length != 4) continue;
				entries.merge(parts[1] + ' ' + parts[3], Integer.parseInt(parts[2]), Integer::sum);
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

			int split = entry.getKey().indexOf(' ');
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
			if (!content.contains(".setMem(") && !content.contains(".of(")) return this;

			String masked = maskCommentsAndStrings(content);
			scanCalls(file, content, masked, SETMEM_CALL,
					args -> isSanctioned(args, masked), RULE, GUIDANCE);
			scanCalls(file, content, masked, OF_CALL,
					this::isSanctionedIngest, OF_RULE, OF_GUIDANCE);
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
				counts.merge(v.getRule() + '\t' + path + ' ' + v.getLine().trim(), 1, Integer::sum);
			}

			StringBuilder out = new StringBuilder();
			for (Map.Entry<String, Integer> entry : counts.entrySet()) {
				int split = entry.getKey().indexOf(' ');
				out.append(entry.getKey(), 0, split).append('\t')
						.append(entry.getValue()).append('\t')
						.append(entry.getKey().substring(split + 1)).append('\n');
			}

			Files.writeString(Path.of(args[2]), out.toString());
		} else {
			detector.log(detector.generateReport());
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
	 * Determines whether a {@code PackedCollection.of} argument list is sanctioned: every
	 * argument must be a numeric literal. Unlike {@code setMem} there is no offset argument,
	 * so no identifier of any kind is permitted — a host array, a {@code List}, a stream
	 * pipeline, or a computed scalar are all violations.
	 *
	 * @param argString  the raw text between the call's parentheses (comment/string masked)
	 * @return           {@code true} if the call is sanctioned
	 */
	private boolean isSanctionedIngest(String argString) {
		List<String> args = splitTopLevel(argString);
		if (args.isEmpty()) return false;

		for (String arg : args) {
			if (isArrayish(arg) || !isNumericLiteral(arg)) return false;
		}
		return true;
	}

	/**
	 * Determines whether a {@code fill}/{@code pack} argument list is within the scalar
	 * allowance: every argument must be an individual scalar value — never an array, a
	 * device read-back, a lambda, a method reference, or a call (whose result could be an
	 * array the scan cannot see) — and there must be fewer than {@link #SCALAR_ALLOWANCE}
	 * of them. A computed scalar is passed by hoisting it to a local first; a local
	 * declared as an array is recognised and rejected. A zero-argument call transfers
	 * nothing and is permitted (this also covers method declarations such as
	 * {@code Tensor.pack()}).
	 *
	 * @param argString  the raw text between the call's parentheses (comment/string masked)
	 * @param masked     the whole masked file, used to resolve an identifier's declared type
	 * @return           {@code true} if the call is within the allowance
	 */
	private boolean isWithinScalarAllowance(String argString, String masked) {
		List<String> args = splitTopLevel(argString);
		if (args.isEmpty()) return true;
		if (args.size() >= SCALAR_ALLOWANCE) return false;

		for (String arg : args) {
			if (isArrayish(arg) || arg.contains("(") || arg.contains("->") || arg.contains("::")) {
				return false;
			}

			String trimmed = arg.trim();
			if (IDENTIFIER.matcher(trimmed).matches() && isDeclaredArray(masked, trimmed)) {
				return false;
			}
		}
		return true;
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
			if (path.contains(entry[0]) && line.equals(entry[1])) return true;
		}
		return false;
	}

	/**
	 * Determines whether a {@code setMem} argument list is one of the two sanctioned shapes:
	 * a single numeric literal, or an (optional) leading offset followed exclusively by
	 * numeric-literal value arguments. Any array syntax, or an array-typed leading argument,
	 * makes the call a violation.
	 *
	 * @param argString  the raw text between the call's parentheses (comment/string masked)
	 * @param masked     the whole masked file, used to resolve a leading identifier's type
	 * @return           {@code true} if the call is sanctioned
	 */
	private boolean isSanctioned(String argString, String masked) {
		List<String> args = splitTopLevel(argString);
		if (args.isEmpty()) return false;

		// Any array construction, indexing, or device->host read anywhere is forbidden.
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
	 * Returns {@code true} if {@code ident} is declared with an array or varargs type anywhere
	 * in the (masked) file — i.e. {@code T[] ident} or {@code T... ident}.
	 *
	 * @param masked  the comment/string-masked file content
	 * @param ident   the identifier to look up
	 * @return        whether the identifier is declared as a host array
	 */
	private boolean isDeclaredArray(String masked, String ident) {
		Pattern decl = Pattern.compile(
				"[A-Za-z_$][\\w$.]*(?:\\s*<[^;{}=]*>)?\\s*(?:\\[\\s*\\]|\\.\\.\\.)\\s+"
						+ Pattern.quote(ident) + "\\b");
		return decl.matcher(masked).find();
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
