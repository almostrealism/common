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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	/** Matches an AR_TEST_GROUP/AR_TEST_GROUPS reference anywhere in the command. No leading
	 * word-boundary assertion: the real shard invocation shape is {@code -DAR_TEST_GROUP=2},
	 * where "AR_TEST_GROUP" is glued directly to the "-D" property prefix with no boundary
	 * between the "D" and the "A" (both word characters) -- a leading {@code \\b} would never
	 * match that form and would leave the actual incident shape undetected. */
	private static final Pattern AR_TEST_GROUP = Pattern.compile("AR_TEST_GROUPS?\\b");

	/** Matches a whole {@code -DskipTests} argument token, capturing an explicit {@code true}/
	 * {@code false} value when present (a bare flag with no {@code =value} means {@code true}).
	 * Matched per-argument via {@link Matcher#matches()}, not as a substring search, so a
	 * plain occurrence never wrongly reads {@code -DskipTests=false} as bare-true. */
	private static final Pattern SKIP_TESTS_PROP = Pattern.compile(
			"-DskipTests(?:=(true|false))?", Pattern.CASE_INSENSITIVE);

	/** Same shape as {@link #SKIP_TESTS_PROP} for the {@code maven.test.skip} property. */
	private static final Pattern MAVEN_TEST_SKIP_PROP = Pattern.compile(
			"-Dmaven\\.test\\.skip(?:=(true|false))?", Pattern.CASE_INSENSITIVE);

	/** Default-lifecycle phases that run tests unless the effective {@link #SKIP_TESTS_PROP}/
	 * {@link #MAVEN_TEST_SKIP_PROP} value is true. */
	private static final List<String> TEST_RUNNING_PHASES = Arrays.asList(
			"test", "integration-test", "verify", "install", "package", "deploy");

	/** Matches a Maven {@code -Dtest=...} argument, capturing its value. */
	private static final Pattern DTEST_ARG = Pattern.compile("^-Dtest=(.+)$");

	/** Maven launcher executable names recognized by {@link #mavenSegmentViolation}: the plain
	 * {@code mvn} plus the Maven Wrapper scripts ({@code ./mvnw}, {@code ./mvnw.cmd}) and the
	 * standalone Windows batch launcher. Without these, {@code ./mvnw.cmd test -pl engine/utils}
	 * would see a base name of {@code mvnw.cmd} (not {@code mvn}) and be waved through as a
	 * custom command -- {@code baseName()} only strips leading path-directory components, never
	 * file extensions, so it never normalizes {@code mvnw.cmd} to {@code mvnw}. */
	private static final List<String> MVN_LAUNCHER_NAMES = Arrays.asList(
			"mvn", "mvnw", "mvn.cmd", "mvnw.cmd");

	/** Shell control operators that separate one simple command from the next. */
	private static final List<String> SHELL_OPERATORS = Arrays.asList(
			"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "{", "}");

	/** Matches a leading {@code VAR=value} assignment token, as accepted by {@code env}. */
	private static final Pattern ENV_ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=.*$");

	/** Shell interpreters whose {@code -c <script>} form re-executes an inline script string. */
	private static final List<String> SHELL_INTERPRETERS = Arrays.asList(
			"sh", "bash", "zsh", "dash", "ksh");

	/** Command-prefix wrappers that pass their remaining arguments through to the real
	 * command unchanged: a wrapper such as {@code command mvn test} or {@code sudo mvn test}
	 * must not be waved through just because its first token is not literally {@code mvn}/
	 * {@code pytest}. Minus "env" -- env is handled separately by {@link #unwrapEnv} because
	 * it also strips its own {@code VAR=value} assignments and flags. */
	private static final List<String> CMD_PREFIXES = Arrays.asList(
			"!", "time", "nohup", "sudo", "command", "exec", "builtin", "stdbuf", "nice", "ionice");

	/** {@code env} options that consume the following token as their own operand (unless given in
	 * glued {@code --opt=value} form) rather than being a bare flag -- e.g. {@code env -u FOO mvn
	 * test} unsets FOO before running {@code mvn}, so "FOO" must not be mistaken for the wrapped
	 * command's own first token. */
	private static final List<String> ENV_OPTIONS_WITH_OPERAND = Arrays.asList(
			"-u", "--unset", "-C", "--chdir", "-S", "--split-string");

	/** {@link #CMD_PREFIXES} wrapper option flags (keyed by the wrapper's base name) that consume
	 * the following token as their own operand, unless given in glued {@code --opt=value} form --
	 * mirroring {@link #ENV_OPTIONS_WITH_OPERAND} for {@code env}. Without this, e.g. {@code nice
	 * -n 10 mvn test} would strip only "nice" and leave "-n" as the wrapped command's own first
	 * token, never reaching "mvn"; {@code sudo -u user mvn test} has the same problem with "-u". A
	 * wrapper absent from this map, or a flag absent from its set, is still stripped as a bare
	 * flag with no operand by {@link #unwrapCmdPrefixOptions} -- it fails toward stripping less,
	 * not toward absorbing an unrecognized flag's operand by mistake. */
	private static final Map<String, List<String>> CMD_PREFIX_OPTIONS_WITH_OPERAND;

	static {
		Map<String, List<String>> options = new HashMap<>();
		options.put("sudo", Arrays.asList("-u", "--user", "-g", "--group", "-h", "--host",
				"-p", "--prompt", "-C", "--close-from", "-R", "--chroot", "-T", "--command-timeout"));
		options.put("nice", Arrays.asList("-n", "--adjustment"));
		options.put("ionice", Arrays.asList("-c", "--class", "-n", "--classdata", "-p", "--pid"));
		options.put("stdbuf", Arrays.asList("-i", "--input", "-o", "--output", "-e", "--error"));
		options.put("time", Arrays.asList("-o", "--output", "-f", "--format"));
		options.put("exec", Arrays.asList("-a", "--as"));
		CMD_PREFIX_OPTIONS_WITH_OPERAND = Collections.unmodifiableMap(options);
	}

	/** Matches a backtick command substitution, capturing its inner text. */
	private static final Pattern BACKTICK_SUBSTITUTION = Pattern.compile("`([^`]*)`");

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
		validateText(command);
		return this;
	}

	/**
	 * Validates a single simple-command token list, first unwrapping a leading
	 * chain of command-prefix wrappers ({@code env VAR=val ...}, {@code sudo},
	 * {@code command}, {@code exec}, ...) and -- when the segment is a shell
	 * interpreter invoked as {@code sh|bash|zsh|dash|ksh -c "<script>"} or
	 * {@code eval <words...>} -- recursing into the inline script's own text
	 * instead of checking the interpreter/eval invocation itself. Without this,
	 * {@code env mvn test}, {@code command mvn test}, {@code sh -c 'mvn test'},
	 * or {@code eval mvn test} would see a first token other than {@code mvn}/
	 * {@code pytest} and be waved through unchecked.
	 */
	private void validateSegment(List<String> tokens) {
		List<String> unwrapped = unwrapCommandPrefixes(tokens);
		String script = shellDashCScript(unwrapped);
		if (script == null) {
			script = evalScript(unwrapped);
		}
		if (script != null) {
			validateText(script);
			return;
		}
		String reason = mavenSegmentViolation(unwrapped);
		if (reason != null) {
			violations.add(reason);
			return;
		}
		reason = pytestSegmentViolation(unwrapped);
		if (reason != null) {
			violations.add(reason);
		}
	}

	/**
	 * Validates {@code text} as a shell command string: its own simple-command
	 * segments plus the contents of any backtick command substitution appearing
	 * anywhere in it. A substitution executes even when the outer command's own
	 * first token (e.g. {@code echo}) is not itself Maven or pytest -- {@code
	 * echo `mvn test -pl engine/utils`} is tokenized as an {@code echo} segment,
	 * but the shell still runs the embedded {@code mvn test} to produce echo's
	 * argument.
	 */
	private void validateText(String text) {
		for (List<String> inner : segmentsForText(text)) {
			validateSegment(inner);
		}
		for (String substitution : commandSubstitutions(text)) {
			validateText(substitution);
		}
	}

	/**
	 * Strips a leading {@code env} invocation's {@code VAR=value} assignments
	 * and flags (e.g. {@code -i}), returning the wrapped command's own tokens.
	 * Returns {@code tokens} unchanged when it is not an {@code env} invocation.
	 *
	 * <p>A flag in {@link #ENV_OPTIONS_WITH_OPERAND} (e.g. {@code -u}) consumes
	 * the next token as its own operand unless given in glued {@code
	 * --opt=value} form -- without this, {@code env -u FOO mvn test} would
	 * treat {@code FOO} as the wrapped command's own first token instead of
	 * skipping it, and never recognize {@code mvn} at all.</p>
	 */
	private List<String> unwrapEnv(List<String> tokens) {
		if (tokens.isEmpty() || !"env".equals(baseName(tokens.get(0)))) {
			return tokens;
		}
		int i = 1;
		while (i < tokens.size()) {
			String tok = tokens.get(i);
			if (ENV_ASSIGNMENT.matcher(tok).matches()) {
				i++;
				continue;
			}
			if (!tok.startsWith("-")) {
				break;
			}
			i++;
			if (ENV_OPTIONS_WITH_OPERAND.contains(tok) && !tok.contains("=") && i < tokens.size()) {
				i++;
			}
		}
		return tokens.subList(i, tokens.size());
	}

	/**
	 * Returns the inline script text when {@code tokens} is {@code eval
	 * <words...>}, or {@code null} when it is not that shape. Mirrors the
	 * shell's own behaviour of concatenating eval's arguments with spaces and
	 * re-parsing the result as a new command line -- without this, {@code eval
	 * mvn test -pl engine/utils} would see a first token of {@code eval} (not
	 * {@code mvn}) and be waved through unchecked.
	 */
	private String evalScript(List<String> tokens) {
		if (tokens.isEmpty() || !"eval".equals(baseName(tokens.get(0))) || tokens.size() < 2) {
			return null;
		}
		return String.join(" ", tokens.subList(1, tokens.size()));
	}

	/**
	 * Extracts the inner command text of every backtick command substitution
	 * appearing anywhere in {@code text}.
	 */
	private List<String> commandSubstitutions(String text) {
		List<String> results = new ArrayList<>();
		Matcher matcher = BACKTICK_SUBSTITUTION.matcher(text);
		while (matcher.find()) {
			results.add(matcher.group(1));
		}
		results.addAll(dollarParenSubstitutions(text));
		return results;
	}

	/**
	 * Extracts the inner command text of every {@code $(...)} command
	 * substitution in {@code text}, honoring balanced parentheses so a nested
	 * {@code $( ... $(...) ... )} is not truncated at the first closing paren.
	 */
	private List<String> dollarParenSubstitutions(String text) {
		List<String> results = new ArrayList<>();
		int n = text.length();
		int i = 0;
		while (i < n) {
			if (text.charAt(i) == '$' && i + 1 < n && text.charAt(i + 1) == '(') {
				int depth = 1;
				int j = i + 2;
				int start = j;
				while (j < n && depth > 0) {
					char c = text.charAt(j);
					if (c == '(') {
						depth++;
					} else if (c == ')') {
						depth--;
					}
					j++;
				}
				if (depth == 0) {
					results.add(text.substring(start, j - 1));
					i = j;
					continue;
				}
			}
			i++;
		}
		return results;
	}

	/**
	 * Strips a leading chain of command-prefix wrappers -- {@code env} (with
	 * its own {@code VAR=value} assignments and flags), bare {@code
	 * VAR=value} assignments with no leading {@code env} token (the shell
	 * accepts one or more of these directly in command position, e.g.
	 * {@code FOO=bar mvn test}), and simple wrappers in {@link #CMD_PREFIXES}
	 * ({@code sudo}, {@code nohup}, {@code time}, {@code exec}, {@code
	 * command}, {@code builtin}, {@code stdbuf}, {@code nice}, {@code
	 * ionice}, {@code !}) -- along with any of that wrapper's own option
	 * flags and, for a recognized flag, its operand (see
	 * {@link #unwrapCmdPrefixOptions}) -- so e.g. {@code command mvn test},
	 * {@code sudo env FOO=bar mvn test}, {@code nice -n 10 mvn test}, or
	 * {@code FOO=bar mvn test} reach the real command. Returns {@code
	 * tokens} unchanged when it starts with none of these.
	 */
	private List<String> unwrapCommandPrefixes(List<String> tokens) {
		while (!tokens.isEmpty()) {
			List<String> afterEnv = unwrapEnv(tokens);
			if (afterEnv != tokens) {
				tokens = afterEnv;
				continue;
			}
			List<String> afterAssignments = unwrapLeadingAssignments(tokens);
			if (afterAssignments != tokens) {
				tokens = afterAssignments;
				continue;
			}
			String base = baseName(tokens.get(0));
			if (CMD_PREFIXES.contains(base)) {
				tokens = unwrapCmdPrefixOptions(base, tokens.subList(1, tokens.size()));
				continue;
			}
			break;
		}
		return tokens;
	}

	/**
	 * Strips the option flags -- and, for a recognized wrapper/flag pair in
	 * {@link #CMD_PREFIX_OPTIONS_WITH_OPERAND}, their operands -- that
	 * immediately follow a stripped {@link #CMD_PREFIXES} wrapper name, so
	 * the wrapped command's own first token (the thing actually executed) is
	 * what {@link #mavenSegmentViolation} and {@link #pytestSegmentViolation}
	 * see. Without this, {@code nice -n 10 mvn test} or {@code sudo -u user
	 * mvn test} would leave {@code -n}/{@code -u} as the apparent command,
	 * never reaching {@code mvn}. Stops at the first non-flag token, or after
	 * a bare {@code --} end-of-options marker.
	 */
	private List<String> unwrapCmdPrefixOptions(String wrapperBase, List<String> tokens) {
		List<String> operandFlags = CMD_PREFIX_OPTIONS_WITH_OPERAND.getOrDefault(
				wrapperBase, Collections.emptyList());
		int i = 0;
		while (i < tokens.size() && tokens.get(i).startsWith("-") && !"--".equals(tokens.get(i))) {
			String flag = tokens.get(i);
			i++;
			if (operandFlags.contains(flag) && !flag.contains("=") && i < tokens.size()) {
				i++;
			}
		}
		if (i < tokens.size() && "--".equals(tokens.get(i))) {
			i++;
		}
		return tokens.subList(i, tokens.size());
	}

	/**
	 * Strips one or more leading bare {@code VAR=value} assignment tokens
	 * (as the shell accepts directly in command position, with no {@code
	 * env} keyword), returning {@code tokens} unchanged when it does not
	 * start with one.
	 */
	private List<String> unwrapLeadingAssignments(List<String> tokens) {
		int i = 0;
		while (i < tokens.size() && ENV_ASSIGNMENT.matcher(tokens.get(i)).matches()) {
			i++;
		}
		return i == 0 ? tokens : tokens.subList(i, tokens.size());
	}

	/**
	 * Returns the inline script text when {@code tokens} is a shell interpreter
	 * invoked with a {@code -c} option -- whether as its own token
	 * ({@code sh -c "<script>"}) or combined with other short options in the
	 * same token ({@code bash -ec "<script>"}, {@code bash -e -c "<script>"}) --
	 * or {@code null} when it is not that shape.
	 *
	 * <p>Walks the leading run of single-dash short-option tokens (stopping at
	 * the first token that is not one, a {@code --} form, or the end of the
	 * list) looking for one containing {@code c}. Mirrors {@code getopt}: any
	 * characters in that token after the {@code c} are its glued-on argument
	 * ({@code -cSCRIPT}); when none remain, the following whole token is the
	 * argument instead ({@code -ec "<script>"}). Without this, {@code bash -ec
	 * 'mvn test -pl engine/utils'} would see option token {@code -ec}, not
	 * literally {@code -c}, and be waved through as an unrecognized interpreter
	 * invocation instead of having its embedded script inspected.</p>
	 */
	private String shellDashCScript(List<String> tokens) {
		if (tokens.size() < 2 || !SHELL_INTERPRETERS.contains(baseName(tokens.get(0)))) {
			return null;
		}
		for (int i = 1; i < tokens.size(); i++) {
			String tok = tokens.get(i);
			if (!tok.startsWith("-") || tok.startsWith("--")) {
				return null;
			}
			int cIndex = tok.indexOf('c', 1);
			if (cIndex < 0) {
				continue;
			}
			String remainder = tok.substring(cIndex + 1);
			if (!remainder.isEmpty()) {
				return remainder;
			}
			return i + 1 < tokens.size() ? tokens.get(i + 1) : null;
		}
		return null;
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

	/**
	 * Returns the effective boolean value of a Maven skip property (e.g.
	 * {@code skipTests}) given every matching token in {@code args}, or
	 * {@code null} when the property never appears. Maven system properties
	 * set via repeated {@code -D} take the LAST occurrence's value, so a
	 * command such as {@code -DskipTests -DskipTests=false} does NOT skip
	 * tests even though an earlier flag says otherwise -- returning as soon
	 * as any matching flag is seen, regardless of order, would wrongly
	 * accept that command as build-only. A bare flag with no {@code =value}
	 * means {@code true}.
	 */
	private static Boolean effectiveSkipValue(List<String> args, Pattern pattern) {
		Boolean value = null;
		for (String arg : args) {
			Matcher m = pattern.matcher(arg);
			if (m.matches()) {
				String explicit = m.group(1);
				value = explicit == null || "true".equalsIgnoreCase(explicit);
			}
		}
		return value;
	}

	/** Returns a violation reason for a Maven segment, or null when it is acceptable. */
	private String mavenSegmentViolation(List<String> tokens) {
		if (tokens.isEmpty() || !MVN_LAUNCHER_NAMES.contains(baseName(tokens.get(0)))) {
			return null;
		}
		List<String> args = tokens.subList(1, tokens.size());
		if (Boolean.TRUE.equals(effectiveSkipValue(args, SKIP_TESTS_PROP))
				|| Boolean.TRUE.equals(effectiveSkipValue(args, MAVEN_TEST_SKIP_PROP))) {
			return null;
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

	/** True when {@code value} is exactly one non-empty {@code Class#method} entry.
	 *
	 * <p>A {@code -Dtest} value may name several comma-separated entries, but Maven runs all of
	 * them in a single invocation -- accepting more than one, even when each individually names a
	 * method, would still let one command run multiple tests, contradicting the "at most ONE test
	 * per invocation" rule this validator otherwise enforces (e.g. via the pytest and MCP runner
	 * checks). Only a single {@code Class#method} entry is narrow enough.</p>
	 */
	private boolean dtestIsNarrow(String value) {
		List<String> entries = new ArrayList<>();
		for (String entry : value.split(",")) {
			if (!entry.isEmpty()) {
				entries.add(entry);
			}
		}
		return entries.size() == 1 && entries.get(0).contains("#");
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
		if (positionals.size() == 1 && positionals.get(0).contains("::")) {
			return null;
		}
		if (positionals.size() > 1) {
			return "pytest command names " + positionals.size() + " positional arguments in \""
					+ String.join(" ", tokens) + "\". Even when each one is an explicit node id, "
					+ "pytest runs them together in a single invocation, which agents and job "
					+ "submitters may never do. Pass exactly one node id per invocation.";
		}
		return "pytest command has no explicit node id (file.py::test_name): \""
				+ String.join(" ", tokens) + "\". This runs an entire file or directory. Pass "
				+ "explicit node ids, one test per invocation.";
	}

	/**
	 * Splits {@code text} into simple-command token lists, first splitting on
	 * newlines and then on {@link #SHELL_OPERATORS} within each line. Newlines
	 * are handled as an explicit pre-split rather than as another entry in
	 * {@link #SHELL_OPERATORS}: {@link #tokenize} treats any
	 * {@link Character#isWhitespace} character, including {@code '\n'}, purely
	 * as a token separator, never as a token in its own right, so a multi-line
	 * command such as {@code "mvn test -Dtest=Foo#bar\nmvn test -pl
	 * engine/utils"} would otherwise tokenize as one unbroken segment -- letting
	 * the narrow selector on the first line mask the second line's broad
	 * invocation.
	 */
	private List<List<String>> segmentsForText(String text) {
		List<List<String>> segments = new ArrayList<>();
		for (String line : text.split("\n", -1)) {
			segments.addAll(splitIntoSegments(tokenize(line)));
		}
		return segments;
	}

	/** Groups {@code tokens} into simple-command segments, split on {@link #SHELL_OPERATORS}. */
	private List<List<String>> splitIntoSegments(List<String> tokens) {
		List<List<String>> segments = new ArrayList<>();
		List<String> current = new ArrayList<>();
		for (String token : tokens) {
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
