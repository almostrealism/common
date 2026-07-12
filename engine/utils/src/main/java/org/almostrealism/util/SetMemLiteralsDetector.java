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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces that {@code setMem(...)} writes device memory only from numeric literals.
 *
 * <p>A device-memory write may enter the framework by exactly two routes:
 * <ul>
 *   <li>copying from another {@link org.almostrealism.hardware.MemoryData} — expressed
 *       through the distinctly-named {@code setFrom(...)} surface (or, better, a tracked
 *       {@code cp(src).into(dest).evaluate()} assignment); or</li>
 *   <li>assigning numeric literals written directly in the source, which is convenient
 *       for small constant vectors: {@code setMem(0, 1.0, 2.0)} / {@code setMem(i, 0.0)}.</li>
 * </ul>
 *
 * <p>Every other {@code setMem} argument shape is a violation. In particular a host
 * {@code double[]}/{@code float[]} (identifier, {@code new double[...]}, an array index,
 * or a {@code toArray()}/{@code toDouble()}/{@code toFloat()} result) and any computed
 * scalar (a variable, cast, or arithmetic expression) are forbidden: if a value is being
 * <em>computed</em>, it must be produced by a {@link io.almostrealism.relation.Producer}
 * and materialised with {@code fill(value)}, {@code fill(pos -> ...)}, or a producer
 * assignment — never staged in a Java array and uploaded.
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

	/** Guidance appended to every violation, naming the sanctioned idioms. */
	private static final String GUIDANCE =
			"setMem writes device memory only from numeric literals (e.g. setMem(0, 1.0, 2.0)). "
					+ "To copy from another MemoryData use setFrom(...) or cp(src).into(dest).evaluate(); "
					+ "to materialise computed values use a Producer with fill(value) / fill(pos -> ...) "
					+ "or a producer assignment. A host double[]/float[] must never be uploaded via setMem.";

	/**
	 * File name fragments of the framework's sanctioned write surface: the classes that
	 * implement the array-accepting overloads, the low-level host&harr;device primitive, and the
	 * collection-population idioms this rule redirects authors toward ({@code fill},
	 * {@code replace}, {@code clone}, and the from-host factories on {@code PackedCollection}).
	 * These are the one legitimate home of a bulk host-array write; every other file is subject
	 * to the rule.
	 */
	private static final List<String> SANCTIONED_WRITE_SURFACE = List.of(
			"/hardware/MemoryData.java",
			"/hardware/mem/MemoryDataAdapter.java",
			"/code/Memory.java",
			"MemoryProvider.java",           // matches every *MemoryProvider implementation
			"/collect/PackedCollection.java", // implements fill/replace/clone and from-host factories
			"/collect/CollectionCreationFeatures.java", // c(double...) — the host-array to collection ingest primitive
			// RGB and its backing store are PackedCollection value types (three host-side doubles
			// mutated per-channel); treated exactly like PackedCollection itself.
			"/color/RGB.java",
			"/color/RGBData192.java"
	);

	/**
	 * Module directory fragments whose {@code setMem} violations are temporarily tolerated because
	 * that module has not yet been migrated under the phased roll-out. Enforcement is turned on one
	 * module at a time; a module is removed from this list in the phase that migrates it, so that a
	 * failing phase exposes only that module's changes. A file whose path contains any fragment here
	 * is skipped entirely by this rule (its other detectors still apply).
	 */
	private static final List<String> UNMIGRATED_MODULES = List.of(
			"/engine/audio/",
			"/engine/utils/",
			"/extern/ml-onnx/",
			"/flowtree/graphpersist/",
			"/studio/compose/",
			"/studio/experiments/",
			"/studio/music/",
			"/studio/spatial/"
	);

	/**
	 * Burn-down whitelist of individually-acknowledged violations in already-enforced modules that
	 * could not be migrated to a producer/{@code setFrom} idiom. An entry suppresses a single call
	 * only when the file path contains {@code pathFragment} and the offending source line, trimmed,
	 * is exactly {@code sourceLine}, so the entry re-triggers the moment the line is edited. Every
	 * entry here is a write below the producer API in {@code base/hardware} (which cannot import the
	 * collect layer) or the randomness ingest primitive; these are expected to shrink to zero.
	 */
	private static final List<String[]> KNOWN_EXCLUSIONS = List.of(
			new String[] {"/hardware/HardwareFeatures.java", "counter.setMem(0, count);"},
			new String[] {"/hardware/computations/Periodic.java", "counter.setMem(0, count);"},
			new String[] {"/hardware/mem/MemoryDataCacheManager.java", "getData().setMem(entrySize * index, data);"},
			new String[] {"/collect/computations/Random.java", "((MemoryBank) destination).setMem(values);"},
			new String[] {"/space/CachedMeshIntersectionKernel.java",
					"((MemoryData) ((MemoryBank) destination).get(i)).setMem(cache.toDouble(i * 2), 1.0);"},
			new String[] {"/space/MeshData.java", "destination.setMem(i, result.toDouble(i * 2));"},
			new String[] {"/assets/CollectionEncoder.java", "destination.setMem(destinationOffset, f);"},
			new String[] {"/assets/CollectionEncoder.java", "destination.setMem(destinationOffset,"},
			new String[] {"/llama2/Llama2Weights.java", "c.setMem(0, data, 0, shape.getTotalSize());"},
			new String[] {"SAMEResamplingParityTest.java", "pc.setMem(0, data, 0, data.length);"},
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

	/** Locates the start of each {@code .setMem(} call. */
	private static final Pattern SETMEM_CALL = Pattern.compile("\\.setMem\\s*\\(");

	/** A bare Java identifier (used to recognise a lone offset/source argument). */
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

	/**
	 * Creates a detector that will scan Java source files under the given directory.
	 *
	 * @param rootDir  the root directory to scan
	 */
	public SetMemLiteralsDetector(Path rootDir) {
		super(rootDir);
	}

	/**
	 * Scans a single file for non-literal {@code setMem} argument usage.
	 *
	 * @param file  the file to scan
	 * @return this detector for chaining
	 */
	@Override
	public SetMemLiteralsDetector scanFile(Path file) {
		if (isExcluded(file) || isSanctionedWriteSurface(file) || isUnmigratedModule(file)) return this;

		try {
			String content = Files.readString(file);
			if (!content.contains(".setMem(")) return this;

			String masked = maskCommentsAndStrings(content);
			Matcher call = SETMEM_CALL.matcher(masked);
			while (call.find()) {
				int argsStart = call.end();
				int argsEnd = matchingParen(masked, argsStart);
				if (argsEnd < 0) continue;

				String argString = masked.substring(argsStart, argsEnd);
				if (!isSanctioned(argString, masked)) {
					int lineNum = countLines(content, call.start());
					if (isKnownExclusion(file, lineText(content, lineNum))) continue;
					violations.add(new Violation(file, lineNum, lineText(content, lineNum),
							RULE, GUIDANCE));
				}
			}
		} catch (IOException e) {
			warn("Could not read file " + file, e);
		}

		return this;
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
	 * Returns {@code true} if the file belongs to a module that has not yet been migrated under the
	 * phased roll-out and is therefore temporarily exempt from this rule.
	 *
	 * @param file  the file to test
	 * @return      whether the file is in an as-yet-unmigrated module
	 */
	private boolean isUnmigratedModule(Path file) {
		String path = file.toString().replace('\\', '/');
		for (String fragment : UNMIGRATED_MODULES) {
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
