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

package io.flowtree.jobs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces the "no broad test runs" rule on a job submission's shell
 * command ({@code command} for a shell-command job, {@code
 * postCompletionCommand} for a coding-agent job) at the controller.
 *
 * <p>On 2026-09-16 an operator-side assistant submitted a job whose
 * {@code postCompletionCommand} ran a full {@code mvn install} followed by
 * an entire {@code engine/utils} CI shard ({@code -DAR_TEST_GROUP}/
 * {@code -DAR_TEST_GROUPS}), with a 3600s timeout and 2 retries. It burned
 * over 3 hours on a macOS runner, and the retry sessions then weakened
 * pre-existing tests to force a pass. This validator is the controller-side
 * half of that rule's enforcement, so a direct API call to
 * {@code /api/submit} cannot bypass what {@code ar-manager}'s
 * {@code workstream_submit_task} already rejects. It mirrors
 * {@code tools/mcp/manager/test_execution_limits.py}'s
 * {@code validate_post_completion_command}; keep the two in sync.</p>
 *
 * <p>There is no bypass for this check. A command may never run a Maven
 * test-executing phase (test/integration-test/verify/install/package/
 * deploy) without an explicit {@code Class#method} selector, reference
 * {@code AR_TEST_GROUP}/{@code AR_TEST_GROUPS}, or run pytest against a
 * directory or whole file instead of an explicit node id. The sibling
 * timeout ceiling ({@link #MAX_TIMEOUT_SECONDS}) is enforced separately by
 * clamping at the call site in {@code FlowTreeApiEndpoint#handleSubmit},
 * since an over-limit timeout is corrected rather than rejected.</p>
 */
public class PostCompletionCommandValidator {

	/** Maximum wall-clock budget for a post-completion / shell-job command: 2400s (40 minutes). */
	public static final int MAX_TIMEOUT_SECONDS = 2400;

	/** Matches an AR_TEST_GROUP/AR_TEST_GROUPS reference anywhere in the command. */
	private static final Pattern AR_TEST_GROUP = Pattern.compile("\\bAR_TEST_GROUPS?\\b");

	/** Matches a whole argument token that disables test execution. Matched per-argument via
	 * {@link Matcher#matches()}, not as a substring search, so {@code -DskipTests=false} (which
	 * starts with the same prefix but explicitly re-enables tests) is not misread as a skip flag. */
	private static final Pattern SKIP_TESTS = Pattern.compile(
			"-DskipTests(=true)?|-Dmaven\\.test\\.skip(=true)?", Pattern.CASE_INSENSITIVE);

	/** Default-lifecycle phases that run tests unless {@link #SKIP_TESTS} is present. */
	private static final List<String> TEST_RUNNING_PHASES = Arrays.asList(
			"test", "integration-test", "verify", "install", "package", "deploy");

	/** Matches a Maven {@code -Dtest=...} argument, capturing its value. */
	private static final Pattern DTEST_ARG = Pattern.compile("^-Dtest=(.+)$");

	/** Shell control operators that separate one simple command from the next. */
	private static final List<String> SHELL_OPERATORS = Arrays.asList(
			"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "{", "}");

	/** The shell command being validated. */
	private final String command;

	/** Violations found by {@link #validate()}, in the order they were discovered. */
	private final List<String> violations = new ArrayList<>();

	/**
	 * Creates a validator for the given command; call {@link #validate()} to run it.
	 *
	 * @param command the shell command to validate, possibly null or blank
	 */
	public PostCompletionCommandValidator(String command) {
		this.command = command;
	}

	/** Runs the checks, populating {@link #getViolations()}, and returns this instance. */
	public PostCompletionCommandValidator validate() {
		if (command == null || command.trim().isEmpty()) {
			return this;
		}
		Matcher shardMatch = AR_TEST_GROUP.matcher(command);
		if (shardMatch.find()) {
			violations.add("Command references AR_TEST_GROUP/AR_TEST_GROUPS: \""
					+ truncate(command.trim(), 200) + "\". CI-shard partitioning is reserved "
					+ "for the CI workflow matrix; agents and job submitters must never run a shard.");
		}
		for (List<String> segment : shellSegments()) {
			String reason = mavenSegmentViolation(segment);
			if (reason != null) {
				violations.add(reason);
				continue;
			}
			reason = pytestSegmentViolation(segment);
			if (reason != null) {
				violations.add(reason);
			}
		}
		return this;
	}

	/** Returns the violations found by {@link #validate()}; empty until called. */
	public List<String> getViolations() {
		return violations;
	}

	/** Returns true when {@link #validate()} found at least one violation. */
	public boolean hasViolations() {
		return !violations.isEmpty();
	}

	/** Renders {@link #getViolations()} as a rejection message for the submitter. */
	public String formatRejection() {
		StringBuilder sb = new StringBuilder(
				"Command would run a broad test set, which agents and job submitters may "
				+ "never do -- broad verification belongs to CI. There is no bypass for this "
				+ "check.\n\nViolations found:\n");
		for (String violation : violations) {
			sb.append("  - ").append(violation).append('\n');
		}
		sb.append("\nRewrite the command to select explicit Class#method tests (Maven) or "
				+ "explicit node ids (pytest), one test per invocation.");
		return sb.toString();
	}

	/** Returns a violation reason for a Maven segment, or null when it is acceptable. */
	private String mavenSegmentViolation(List<String> tokens) {
		if (tokens.isEmpty() || !"mvn".equals(baseName(tokens.get(0)))) {
			return null;
		}
		List<String> args = tokens.subList(1, tokens.size());
		for (String arg : args) {
			if (SKIP_TESTS.matcher(arg).matches()) {
				return null;
			}
		}
		List<String> phasesPresent = new ArrayList<>();
		List<String> dtestValues = new ArrayList<>();
		for (String arg : args) {
			if (TEST_RUNNING_PHASES.contains(arg)) {
				phasesPresent.add(arg);
			}
			Matcher m = DTEST_ARG.matcher(arg);
			if (m.matches()) {
				dtestValues.add(m.group(1));
			}
		}
		if (phasesPresent.isEmpty() && dtestValues.isEmpty()) {
			return null;
		}
		String rendered = String.join(" ", tokens);
		if (dtestValues.isEmpty()) {
			return "Maven command runs a test-executing phase (" + String.join(", ", phasesPresent)
					+ ") with no -Dtest selector: \"" + rendered + "\". This runs the module's "
					+ "whole test suite. Pass -Dtest=Class#method for each test, or add "
					+ "-DskipTests if this command is only meant to build.";
		}
		for (String value : dtestValues) {
			if (!dtestIsNarrow(value)) {
				return "Maven -Dtest=" + value + " in \"" + rendered + "\" does not select "
						+ "explicit Class#method tests. A bare class selector (or none) runs "
						+ "every test in that class or module. Use Class#method for each test, "
						+ "one per invocation.";
			}
		}
		return null;
	}

	/** True when every comma-separated {@code -Dtest} entry in {@code value} names a method. */
	private boolean dtestIsNarrow(String value) {
		String[] entries = value.split(",");
		boolean any = false;
		for (String entry : entries) {
			if (entry.isEmpty()) {
				continue;
			}
			any = true;
			if (!entry.contains("#")) {
				return false;
			}
		}
		return any;
	}

	/** Returns a violation reason for a pytest segment, or null when it is acceptable. */
	private String pytestSegmentViolation(List<String> tokens) {
		if (tokens.isEmpty()) {
			return null;
		}
		String base = baseName(tokens.get(0));
		List<String> rest = tokens.subList(1, tokens.size());
		if (("python".equals(base) || "python3".equals(base)) && rest.size() >= 2
				&& "-m".equals(rest.get(0)) && "pytest".equals(rest.get(1))) {
			rest = rest.subList(2, rest.size());
		} else if (!"pytest".equals(base) && !"py.test".equals(base)) {
			return null;
		}
		List<String> positionals = new ArrayList<>();
		for (String arg : rest) {
			if (!arg.startsWith("-")) {
				positionals.add(arg);
			}
		}
		boolean allNodeIds = !positionals.isEmpty();
		for (String positional : positionals) {
			if (!positional.contains("::")) {
				allNodeIds = false;
				break;
			}
		}
		if (allNodeIds) {
			return null;
		}
		return "pytest command has no explicit node id (file.py::test_name): \""
				+ String.join(" ", tokens) + "\". This runs an entire file or directory. Pass "
				+ "explicit node ids, one test per invocation.";
	}

	/**
	 * Splits {@link #command} into simple-command token lists, one per
	 * {@code &&}/{@code ;}/{@code |}-separated segment. A best-effort
	 * whitespace/quote tokenizer -- not a full shell grammar -- since the
	 * only goal is recognising an {@code mvn}/{@code pytest} invocation and
	 * its flags, not executing the command.
	 */
	private List<List<String>> shellSegments() {
		List<List<String>> segments = new ArrayList<>();
		List<String> current = new ArrayList<>();
		for (String token : tokenize(command)) {
			if (SHELL_OPERATORS.contains(token)) {
				if (!current.isEmpty()) {
					segments.add(current);
					current = new ArrayList<>();
				}
			} else {
				current.add(token);
			}
		}
		if (!current.isEmpty()) {
			segments.add(current);
		}
		return segments;
	}

	/** Splits {@code text} on whitespace and shell operators, honouring quotes. */
	private List<String> tokenize(String text) {
		List<String> tokens = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inSingle = false;
		boolean inDouble = false;
		boolean haveToken = false;
		int i = 0;
		int n = text.length();
		while (i < n) {
			char c = text.charAt(i);
			if (inSingle) {
				if (c == '\'') {
					inSingle = false;
				} else {
					current.append(c);
				}
				i++;
				continue;
			}
			if (inDouble) {
				if (c == '"') {
					inDouble = false;
				} else {
					current.append(c);
				}
				i++;
				continue;
			}
			if (c == '\'') {
				inSingle = true;
				haveToken = true;
				i++;
				continue;
			}
			if (c == '"') {
				inDouble = true;
				haveToken = true;
				i++;
				continue;
			}
			if (Character.isWhitespace(c)) {
				if (haveToken) {
					tokens.add(current.toString());
					current.setLength(0);
					haveToken = false;
				}
				i++;
				continue;
			}
			String twoChar = i + 1 < n ? text.substring(i, i + 2) : "";
			if (SHELL_OPERATORS.contains(twoChar)) {
				if (haveToken) {
					tokens.add(current.toString());
					current.setLength(0);
					haveToken = false;
				}
				tokens.add(twoChar);
				i += 2;
				continue;
			}
			String oneChar = String.valueOf(c);
			if (SHELL_OPERATORS.contains(oneChar)) {
				if (haveToken) {
					tokens.add(current.toString());
					current.setLength(0);
					haveToken = false;
				}
				tokens.add(oneChar);
				i++;
				continue;
			}
			current.append(c);
			haveToken = true;
			i++;
		}
		if (haveToken) {
			tokens.add(current.toString());
		}
		return tokens;
	}

	/** Returns the last path component of {@code token} (e.g. {@code mvn} from {@code /usr/bin/mvn}). */
	private static String baseName(String token) {
		int slash = token.lastIndexOf('/');
		return slash < 0 ? token : token.substring(slash + 1);
	}

	/** Truncates {@code s} to at most {@code max} characters. */
	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}
}
