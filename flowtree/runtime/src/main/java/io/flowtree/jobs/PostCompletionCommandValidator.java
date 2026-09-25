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
 * {@code tools/mcp/manager/execution_limits.py}'s
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

	/** Matches a whole {@code -DskipTests} argument token, capturing the assigned value when
	 * present (a bare flag with no {@code =value} means {@code true}). The value group captures
	 * the WHOLE value, not just a literal {@code true}/{@code false}, so a dynamic value such as
	 * {@code -DskipTests=$(printf false)} or {@code -DskipTests=$SKIP} is recognized as an
	 * occurrence of the property and classified as non-skipping by {@link #classifySkipValue}
	 * rather than leaving the {@code -DskipTests} prefix to read as a bare (true) flag while the
	 * shell-supplied value is ignored. Matched per-argument via {@link Matcher#matches()}, not as
	 * a substring search. */
	private static final Pattern SKIP_TESTS_PROP = Pattern.compile(
			"-DskipTests(?:=(.*))?", Pattern.CASE_INSENSITIVE);

	/** Same shape as {@link #SKIP_TESTS_PROP} for the {@code maven.test.skip} property. */
	private static final Pattern MAVEN_TEST_SKIP_PROP = Pattern.compile(
			"-Dmaven\\.test\\.skip(?:=(.*))?", Pattern.CASE_INSENSITIVE);

	/** Leading literal {@code true}/{@code false} of a skip value, tolerating trailing text via
	 * the word boundary. Used by {@link #classifySkipValue} with {@link Matcher#lookingAt()}. */
	private static final Pattern SKIP_LITERAL = Pattern.compile("(true|false)\\b", Pattern.CASE_INSENSITIVE);

	/** Default-lifecycle phases that run tests unless the effective {@link #SKIP_TESTS_PROP}/
	 * {@link #MAVEN_TEST_SKIP_PROP} value is true. Package-private (not private) so
	 * {@link PromptTestInstructionLinter} can build its own Maven-phase pattern from the same
	 * list instead of duplicating it. */
	static final List<String> TEST_RUNNING_PHASES = Arrays.asList(
			"test", "integration-test", "verify", "install", "package", "deploy");

	/** Matches a Maven {@code -Dtest=...} argument, capturing its value. */
	private static final Pattern DTEST_ARG = Pattern.compile("^-Dtest=(.+)$");

	/** Maven launcher executable names recognized by {@link #mavenSegmentViolation}: the plain
	 * {@code mvn} plus the Maven Wrapper scripts ({@code ./mvnw}, {@code ./mvnw.cmd}) and the
	 * standalone Windows batch launcher. Without these, {@code ./mvnw.cmd test -pl engine/utils}
	 * would see a base name of {@code mvnw.cmd} (not {@code mvn}) and be waved through as a
	 * custom command -- {@code baseName()} only strips leading path-directory components, never
	 * file extensions, so it never normalizes {@code mvnw.cmd} to {@code mvnw}. Package-private
	 * (not private) so {@link PromptTestInstructionLinter} can build its own Maven-launcher
	 * pattern from the same list instead of duplicating it. */
	static final List<String> MVN_LAUNCHER_NAMES = Arrays.asList(
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
			"!", "time", "nohup", "sudo", "command", "exec", "builtin", "stdbuf", "nice", "ionice",
			"timeout");

	/** {@code env} options that consume the following token as their own operand (unless given in
	 * glued {@code --opt=value} form) rather than being a bare flag -- e.g. {@code env -u FOO mvn
	 * test} unsets FOO before running {@code mvn}, so "FOO" must not be mistaken for the wrapped
	 * command's own first token. Excludes {@code -S}/{@code --split-string}: unlike every other
	 * entry here, that option's operand is not passed through unchanged as the wrapped command's
	 * argument -- {@code env} word-splits it into a brand-new command line and executes that, so
	 * {@link #unwrapEnv} handles it separately via {@link #envSplitStringOperand} instead of
	 * treating it as an operand to skip. */
	private static final List<String> ENV_OPTIONS_WITH_OPERAND = Arrays.asList(
			"-u", "--unset", "-C", "--chdir");

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
		options.put("timeout", Arrays.asList("-s", "--signal", "-k", "--kill-after"));
		CMD_PREFIX_OPTIONS_WITH_OPERAND = Collections.unmodifiableMap(options);
	}

	/** {@link #CMD_PREFIXES} wrappers that take positional operands of their own after their
	 * option flags and before the wrapped command -- {@code timeout DURATION mvn test} -- keyed by
	 * the wrapper's base name to the number of such operands. Without this, {@code timeout 2400
	 * mvn test} would leave "2400" as the wrapped command's apparent first token and never reach
	 * "mvn". */
	private static final Map<String, Integer> CMD_PREFIX_POSITIONAL_OPERANDS =
			Collections.singletonMap("timeout", 1);

	/** Matches a backtick command substitution, capturing its inner text. */
	private static final Pattern BACKTICK_SUBSTITUTION = Pattern.compile("`([^`]*)`");

	/** Sentinel first element used by {@link #unwrapEnv} to signal that it found an
	 * {@code env -S}/{@code --split-string} script rather than an ordinary wrapped command, and
	 * recognized by {@link #isEnvSplitStringResult} to route the second element (the script text)
	 * to {@link #validateText} instead of treating it as a command's own first token. Not a value
	 * any real shell token can equal, since a NUL byte cannot appear in a shell command line. */
	private static final String ENV_SPLIT_SCRIPT_SENTINEL = "\0envSplitScript\0";

	/** Shell control-flow keywords that can precede a segment's real command after operator
	 * splitting -- e.g. {@code if true; then mvn test -pl engine/utils; fi} splits on {@code ;}
	 * into a segment {@code [then, mvn, test, -pl, engine/utils]}, whose first token is {@code
	 * then}, not {@code mvn}. Without stripping these, {@link #mavenSegmentViolation} and
	 * {@link #pytestSegmentViolation} never see the wrapped command at all. */
	private static final List<String> SHELL_CONTROL_WORDS = Arrays.asList(
			"if", "then", "elif", "else", "fi", "while", "until", "do", "done",
			"for", "case", "esac", "select", "function");

	/** Matches a bare shell variable reference used as a whole token, in either the
	 * {@code $VAR} or {@code ${VAR}} form, capturing the variable's name. Used by
	 * {@link #resolveVariableCommand} to recognize {@code $cmd} in {@code cmd='mvn test -pl
	 * engine/utils'; $cmd} as a reference to a variable recorded by
	 * {@link #recordAssignmentOnlySegment} earlier in the same command text. */
	private static final Pattern VARIABLE_REFERENCE = Pattern.compile("^\\$\\{?([A-Za-z_][A-Za-z0-9_]*)\\}?$");

	/** Matches an unresolved shell parameter expansion ({@code $VAR} or {@code ${VAR}}) anywhere
	 * within a token, as opposed to {@link #VARIABLE_REFERENCE} which anchors on a token that is
	 * <em>entirely</em> one reference. Used by {@link #mavenSegmentViolation} and
	 * {@link #dtestIsNarrow} to reject a Maven phase or {@code -Dtest} selector constructed from a
	 * variable the shell expands at run time -- e.g. {@code mvn $MAVEN_GOAL -pl engine/utils} or
	 * {@code -Dtest=$CLASS#$METHOD} -- which this validator cannot resolve statically. The
	 * {@code $(} command-substitution form is deliberately not matched here (the {@code (} is not
	 * a name character); it is handled separately by {@link #containsSubstitutionMarker}. */
	private static final Pattern PARAMETER_EXPANSION = Pattern.compile("\\$\\{?[A-Za-z_]");

	/** {@code python}/{@code python3} interpreter option flags that take no operand of their
	 * own, so they can precede {@code -m} without hiding it -- e.g. {@code python3 -O -m pytest
	 * tests/} must still be recognized as {@code -m pytest} by {@link #indexOfModuleFlag}. Not
	 * exhaustive of every real Python flag, only the ones that could plausibly appear before
	 * {@code -m} in an agent- or job-submitter-constructed command. */
	private static final List<String> PYTHON_NOARG_FLAGS = Arrays.asList(
			"-O", "-OO", "-B", "-b", "-bb", "-d", "-E", "-h", "-i", "-I",
			"-q", "-s", "-S", "-t", "-tt", "-u", "-v", "-x", "-3", "-R");

	/** {@code python}/{@code python3} interpreter option flags that consume the following
	 * token as their own operand, mirroring {@link #PYTHON_NOARG_FLAGS} for flags that are not
	 * bare. */
	private static final List<String> PYTHON_ARG_FLAGS = Arrays.asList("-W", "-X");

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
		if (isEnvSplitStringResult(unwrapped)) {
			validateText(unwrapped.get(1));
			return;
		}
		if (!unwrapped.isEmpty() && isSubstitutionExecutable(unwrapped.get(0))) {
			violations.add("Command position in \"" + String.join(" ", tokens) + "\" is determined "
					+ "by a command substitution ($(...) or `...`), which this validator cannot "
					+ "resolve statically. Do not construct the executed command name via a "
					+ "substitution.");
			return;
		}
		if (!unwrapped.isEmpty() && VARIABLE_REFERENCE.matcher(unwrapped.get(0)).matches()) {
			violations.add("Command position in \"" + String.join(" ", tokens) + "\" is a bare shell "
					+ "variable reference ($VAR or ${VAR}) that this validator cannot resolve "
					+ "statically -- the job environment could set it to a broad command such as "
					+ "\"mvn test -pl engine/utils\" or a whole-suite runner. Name the executable "
					+ "literally, or assign the command text in the same command so it can be "
					+ "inspected.");
			return;
		}
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
			return;
		}
		reason = unittestSegmentViolation(unwrapped);
		if (reason != null) {
			violations.add(reason);
			return;
		}
		reason = bareShellInterpreterViolation(unwrapped);
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
		Map<String, String> variables = new HashMap<>();
		for (List<String> inner : segmentsForText(text)) {
			if (recordAssignmentOnlySegment(inner, variables)) {
				continue;
			}
			List<String> resolved = resolveVariableCommand(inner, variables);
			validateSegment(resolved != null ? resolved : inner);
		}
		for (String substitution : commandSubstitutions(text)) {
			validateText(substitution);
		}
	}

	/**
	 * Records a bare {@code VAR=value} assignment segment (e.g. {@code cmd='mvn test -pl
	 * engine/utils'}, as the shell accepts directly in command position with no {@code env}
	 * keyword) into {@code variables} and returns true, or returns false without recording
	 * anything when {@code segment} is not entirely assignment tokens -- i.e. it also has its
	 * own command to run, which must still be validated as a segment rather than treated purely
	 * as a variable definition. Later tokens override earlier ones, left to right, matching real
	 * shell assignment order.
	 */
	private boolean recordAssignmentOnlySegment(List<String> segment, Map<String, String> variables) {
		if (segment.isEmpty() || !unwrapLeadingAssignments(segment).isEmpty()) {
			return false;
		}
		for (String token : segment) {
			int eq = token.indexOf('=');
			variables.put(token.substring(0, eq), token.substring(eq + 1));
		}
		return true;
	}

	/**
	 * Returns {@code tokens} with a leading bare {@code $VAR}/{@code ${VAR}} reference replaced
	 * by the recorded variable's own (re-tokenized) words, or {@code null} when {@code tokens} is
	 * empty or its first token is not a reference to a variable {@link #recordAssignmentOnlySegment}
	 * already recorded earlier in the same command text.
	 *
	 * <p>Without this, {@code cmd='mvn test -pl engine/utils'; $cmd} assigns the broad command to
	 * {@code cmd} in one segment and executes it by reference in the next -- the shell resolves
	 * {@code $cmd} to the assigned command line, but no first-token check (mvn/pytest/...) can see
	 * that without resolving the reference first.</p>
	 */
	private List<String> resolveVariableCommand(List<String> tokens, Map<String, String> variables) {
		if (tokens.isEmpty()) {
			return null;
		}
		Matcher matcher = VARIABLE_REFERENCE.matcher(tokens.get(0));
		if (!matcher.matches() || !variables.containsKey(matcher.group(1))) {
			return null;
		}
		List<String> resolved = new ArrayList<>(tokenize(variables.get(matcher.group(1))));
		resolved.addAll(tokens.subList(1, tokens.size()));
		return resolved;
	}

	/** True when {@code token} contains a command substitution marker ({@code $(} or a
	 * backtick) -- meaning the shell determines this token's actual text at run time from a
	 * subprocess's output, which this validator cannot resolve statically. */
	private static boolean containsSubstitutionMarker(String token) {
		return token.contains("$(") || token.contains("`");
	}

	/** True when {@code token} contains an unresolved shell parameter expansion ({@code $VAR} or
	 * {@code ${VAR}}), which the shell replaces at run time with a value this validator cannot see.
	 * Used by {@link #mavenSegmentViolation} (positional phase position) and {@link #dtestIsNarrow}
	 * ({@code -Dtest} selector) to reject a test-command argument whose real value is only known
	 * after expansion, e.g. {@code mvn $MAVEN_GOAL} or {@code -Dtest=$CLASS#$METHOD}. */
	private static boolean containsParameterExpansion(String token) {
		return PARAMETER_EXPANSION.matcher(token).find();
	}

	/** True when {@code token} contains a command substitution marker. Used by
	 * {@link #validateSegment} to reject a segment whose executable (first token) is determined
	 * this way, e.g. {@code $(printf mvn) test -pl engine/utils}: the substitution's own inner
	 * command ({@code printf mvn}) is harmless in isolation, but its output becomes the broad
	 * {@code mvn test} command actually executed, which no first-token check can see without
	 * running the substitution. */
	private boolean isSubstitutionExecutable(String token) {
		return containsSubstitutionMarker(token);
	}

	/** Returns a violation reason when any of {@code args} contains a command substitution
	 * marker ({@code $(...)} or a backtick), or {@code null} when none does. A recognized
	 * command's own phase/selector arguments are checked as literal string values elsewhere
	 * (e.g. {@link #TEST_RUNNING_PHASES}, {@link #DTEST_ARG}), so a substitution in argument
	 * position is invisible to those checks even though the shell resolves it before Maven or
	 * Python ever sees the argument -- e.g. {@code mvn $(printf test) -pl engine/utils} has no
	 * literal {@code test} token for the phase check to see, but the shell still substitutes
	 * {@code test} and runs the whole module suite. Rejected outright rather than accepted by
	 * omission, since this validator cannot resolve the substitution's output statically. */
	private String substitutionArgumentViolation(List<String> tokens, List<String> args) {
		for (String arg : args) {
			if (containsSubstitutionMarker(arg)) {
				return "Argument \"" + arg + "\" in \"" + String.join(" ", tokens) + "\" contains "
						+ "a command substitution ($(...) or `...`), which this validator cannot "
						+ "resolve statically. Do not construct a Maven phase, -Dtest selector, or "
						+ "test id via a substitution.";
			}
		}
		return null;
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
	 *
	 * <p>{@code -S}/{@code --split-string} is different in kind, not just
	 * another operand-flag: {@code env} word-splits that operand and executes
	 * the result as a brand-new command line, the same way {@code sh -c}
	 * does -- it does not pass the operand through unchanged as an argument
	 * to the wrapped command. Skipping it like an ordinary operand (as
	 * {@code env -u FOO} does for {@code FOO}) would silently discard {@code
	 * env -S 'mvn test -pl engine/utils'}'s actual payload instead of
	 * validating it, so this returns the {@link #ENV_SPLIT_SCRIPT_SENTINEL}
	 * marker pair instead; see {@link #isEnvSplitStringResult}.</p>
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
			String splitScript = envSplitStringOperand(tok, tokens, i);
			if (splitScript != null) {
				return Arrays.asList(ENV_SPLIT_SCRIPT_SENTINEL, splitScript);
			}
			i++;
			if (ENV_OPTIONS_WITH_OPERAND.contains(tok) && !tok.contains("=") && i < tokens.size()) {
				i++;
			}
		}
		return tokens.subList(i, tokens.size());
	}

	/**
	 * Returns the script text of an {@code env -S}/{@code --split-string} flag at {@code
	 * tokens.get(i)}, in any of its three forms ({@code -S <script>}, glued {@code -S<script>}, or
	 * {@code --split-string=<script>}), or {@code null} when {@code tokens.get(i)} is not that
	 * flag.
	 */
	private String envSplitStringOperand(String tok, List<String> tokens, int i) {
		if ("-S".equals(tok) || "--split-string".equals(tok)) {
			return i + 1 < tokens.size() ? tokens.get(i + 1) : "";
		}
		if (tok.startsWith("--split-string=")) {
			return tok.substring("--split-string=".length());
		}
		if (tok.startsWith("-S") && tok.length() > 2) {
			return tok.substring(2);
		}
		return null;
	}

	/** True when {@code unwrapped} is the {@link #ENV_SPLIT_SCRIPT_SENTINEL} marker pair
	 * {@link #unwrapEnv} returns for an {@code env -S}/{@code --split-string} invocation. */
	private boolean isEnvSplitStringResult(List<String> unwrapped) {
		return unwrapped.size() == 2 && ENV_SPLIT_SCRIPT_SENTINEL.equals(unwrapped.get(0));
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
				int end = balancedParenEnd(text, i + 2);
				if (text.charAt(end - 1) == ')') {
					results.add(text.substring(i + 2, end - 1));
					i = end;
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
	 * {@code FOO=bar mvn test} reach the real command. Also strips a leading
	 * {@link #SHELL_CONTROL_WORDS} keyword (e.g. {@code then}, {@code do}),
	 * since operator-splitting a chain like {@code if true; then mvn test;
	 * fi} leaves the keyword as a segment's first token, in front of the
	 * segment's real command. Returns {@code tokens} unchanged when it
	 * starts with none of these -- or, when {@code tokens} is an {@code env
	 * -S}/{@code --split-string} invocation, returns the
	 * {@link #ENV_SPLIT_SCRIPT_SENTINEL} marker pair from {@link
	 * #unwrapEnv} unchanged, since that shape has no further tokens of its
	 * own left to unwrap.
	 */
	private List<String> unwrapCommandPrefixes(List<String> tokens) {
		while (!tokens.isEmpty()) {
			List<String> afterEnv = unwrapEnv(tokens);
			if (isEnvSplitStringResult(afterEnv)) {
				return afterEnv;
			}
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
			if (SHELL_CONTROL_WORDS.contains(base)) {
				tokens = tokens.subList(1, tokens.size());
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
	 * a bare {@code --} end-of-options marker, then skips the wrapper's own
	 * positional operands listed in {@link #CMD_PREFIX_POSITIONAL_OPERANDS}
	 * (the DURATION of {@code timeout 2400 mvn test}).
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
		i = Math.min(tokens.size(), i + CMD_PREFIX_POSITIONAL_OPERANDS.getOrDefault(wrapperBase, 0));
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
	 * means {@code true}. A dynamic last value (command substitution or
	 * parameter expansion) is classified as non-skipping by
	 * {@link #classifySkipValue}, so {@code -DskipTests=true
	 * -DskipTests=$(printf false)} is not accepted as build-only.
	 */
	private static Boolean effectiveSkipValue(List<String> args, Pattern pattern) {
		Boolean value = null;
		for (String arg : args) {
			Matcher m = pattern.matcher(arg);
			if (m.matches()) {
				value = classifySkipValue(m.group(1));
			}
		}
		return value;
	}

	/**
	 * Classifies the captured value of a Maven skip property into whether it
	 * actually skips tests. {@code null} (a bare flag with no {@code =value})
	 * means true; a literal {@code true}/{@code false} maps to its boolean;
	 * any other value -- an empty string (Maven parses {@code -DskipTests=} as
	 * false), a command substitution ({@code $(...)}), or a parameter
	 * expansion ({@code $VAR}) -- is treated as NOT skipping, since the
	 * validator cannot resolve what the shell expands it to and accepting it
	 * would let {@code -DskipTests=$(printf false)} pass as build-only. Fail
	 * closed.
	 *
	 * <p>Package-private (not private) and static -- it reads no instance state -- so
	 * {@link PromptTestInstructionLinter} can reuse the identical rule for skip-property
	 * mentions found in prompt text instead of duplicating it.</p>
	 */
	static boolean classifySkipValue(String explicit) {
		if (explicit == null) {
			return true;
		}
		Matcher m = SKIP_LITERAL.matcher(explicit.trim());
		if (m.lookingAt()) {
			return "true".equalsIgnoreCase(m.group(1));
		}
		return false;
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
		String substitutionReason = substitutionArgumentViolation(tokens, args);
		if (substitutionReason != null) {
			return substitutionReason;
		}
		for (String arg : args) {
			if (!arg.startsWith("-") && containsParameterExpansion(arg)) {
				return "Positional argument \"" + arg + "\" in \"" + String.join(" ", tokens)
						+ "\" contains an unresolved shell parameter expansion ($VAR or ${VAR}), "
						+ "which the shell expands at run time into a value this validator cannot "
						+ "see -- it could name a test-running phase such as test/verify/install. "
						+ "Use literal Maven phases and -Dtest=Class#method selectors, or add "
						+ "-DskipTests if this command is only meant to build.";
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

	/** True when {@code value} is exactly one {@code Class#method} entry with non-empty,
	 * wildcard-free class and method names.
	 *
	 * <p>A {@code -Dtest} value may name several comma-separated entries, but Maven runs all of
	 * them in a single invocation -- accepting more than one, even when each individually names a
	 * method, would still let one command run multiple tests, contradicting the "at most ONE test
	 * per invocation" rule this validator otherwise enforces (e.g. via the pytest and MCP runner
	 * checks). Only a single {@code Class#method} entry is narrow enough -- and Surefire treats
	 * {@code *} and {@code ?} in either half as wildcards, so e.g. {@code FooTest#test*} or
	 * {@code Foo*#bar} can still select and run several methods/classes in one invocation despite
	 * naming exactly one comma-separated entry with a {@code #} in it.</p>
	 *
	 * <p>Package-private (not private) and static -- it reads no instance state -- so
	 * {@link PromptTestInstructionLinter} can reuse the identical rule instead of duplicating
	 * it for {@code -Dtest=} values found in prompt text.</p>
	 */
	static boolean dtestIsNarrow(String value) {
		List<String> entries = new ArrayList<>();
		for (String entry : value.split(",")) {
			if (!entry.isEmpty()) {
				entries.add(entry);
			}
		}
		if (entries.size() != 1) {
			return false;
		}
		String entry = entries.get(0);
		if (containsParameterExpansion(entry)) {
			return false;
		}
		int hash = entry.indexOf('#');
		if (hash < 0 || entry.indexOf('#', hash + 1) >= 0) {
			return false;
		}
		String className = entry.substring(0, hash);
		String methodName = entry.substring(hash + 1);
		return !className.isEmpty() && !methodName.isEmpty()
				&& !containsSelectorSeparator(className) && !containsSelectorSeparator(methodName);
	}

	/** True when a {@code -Dtest} class or method half contains a Surefire construct that can
	 * select more than one test in a single invocation: a {@code *}/{@code ?} wildcard, or the
	 * {@code +} method-list separator ({@code Class#method1+method2}, the form the repository's
	 * own CI uses at {@code .github/workflows/analysis.yaml}). Rejecting {@code +} alongside the
	 * wildcards keeps {@code FooTest#first+second} from passing as one narrow selector. */
	private static boolean containsSelectorSeparator(String half) {
		return half.indexOf('*') >= 0 || half.indexOf('?') >= 0 || half.indexOf('+') >= 0;
	}

	/**
	 * Returns the index of the {@code -m} token among {@code tokens}, skipping any leading
	 * {@link #PYTHON_NOARG_FLAGS}/{@link #PYTHON_ARG_FLAGS} interpreter option flags that
	 * precede it -- e.g. {@code python3 -O -m pytest tests/} must still be recognized as
	 * {@code -m pytest}, not waved through because {@code -O} occupies the position {@code -m}
	 * is checked at. Returns -1 when {@code -m} is not reachable that way: an unrecognized flag
	 * stops the walk rather than guessing past it, so a genuinely unrecognized interpreter
	 * invocation shape is left to whatever check runs next instead of being silently unwrapped.
	 */
	private int indexOfModuleFlag(List<String> tokens) {
		int i = 0;
		while (i < tokens.size()) {
			String tok = tokens.get(i);
			if ("-m".equals(tok)) {
				return i;
			}
			if (PYTHON_NOARG_FLAGS.contains(tok)) {
				i++;
				continue;
			}
			if (PYTHON_ARG_FLAGS.contains(tok)) {
				i += 2;
				continue;
			}
			return -1;
		}
		return -1;
	}

	/** Returns a violation reason for a pytest segment, or null when it is acceptable. */
	private String pytestSegmentViolation(List<String> tokens) {
		if (tokens.isEmpty()) {
			return null;
		}
		String base = baseName(tokens.get(0));
		List<String> rest = tokens.subList(1, tokens.size());
		if ("python".equals(base) || "python3".equals(base)) {
			int mIndex = indexOfModuleFlag(rest);
			if (mIndex < 0 || mIndex + 1 >= rest.size() || !"pytest".equals(rest.get(mIndex + 1))) {
				return null;
			}
			rest = rest.subList(mIndex + 2, rest.size());
		} else if (!"pytest".equals(base) && !"py.test".equals(base)) {
			return null;
		}
		String substitutionReason = substitutionArgumentViolation(tokens, rest);
		if (substitutionReason != null) {
			return substitutionReason;
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
	 * Returns a violation reason for a {@code python -m unittest} segment, or null when it is
	 * acceptable. Unlike pytest's {@code file.py::test_name} node id, {@code unittest} addresses a
	 * single test with a dotted {@code module.Class.method} path (two or more dots) -- a bare
	 * module or {@code module.Class} still runs every test in it, and {@code discover} explicitly
	 * walks and runs a whole test tree. Without this check, {@code python3 -m unittest discover}
	 * (the CI documentation's own example of a forbidden broad run) passed through both the
	 * Maven and pytest checks unrecognized, and was accepted.
	 */
	private String unittestSegmentViolation(List<String> tokens) {
		if (tokens.isEmpty()) {
			return null;
		}
		String base = baseName(tokens.get(0));
		if (!("python".equals(base) || "python3".equals(base))) {
			return null;
		}
		List<String> rest = tokens.subList(1, tokens.size());
		int mIndex = indexOfModuleFlag(rest);
		if (mIndex < 0 || mIndex + 1 >= rest.size() || !"unittest".equals(rest.get(mIndex + 1))) {
			return null;
		}
		List<String> args = rest.subList(mIndex + 2, rest.size());
		String substitutionReason = substitutionArgumentViolation(tokens, args);
		if (substitutionReason != null) {
			return substitutionReason;
		}
		List<String> positionals = new ArrayList<>();
		for (String arg : args) {
			if (!arg.startsWith("-")) {
				positionals.add(arg);
			}
		}
		if (positionals.size() == 1 && !"discover".equals(positionals.get(0))
				&& countChar(positionals.get(0), '.') >= 2) {
			return null;
		}
		return "python -m unittest command in \"" + String.join(" ", tokens) + "\" does not name "
				+ "a single dotted module.Class.method test id. \"discover\", a bare module, or a "
				+ "module.Class runs many tests at once, which agents and job submitters may "
				+ "never do. Pass exactly one module.Class.method id per invocation.";
	}

	/** Counts occurrences of {@code target} in {@code s}. */
	private static int countChar(String s, char target) {
		int count = 0;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == target) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Returns a violation reason when {@code tokens} invokes a {@link #SHELL_INTERPRETERS}
	 * interpreter with no script-file positional argument -- meaning it reads its script from
	 * standard input, as in {@code printf 'mvn test -pl engine/utils' | sh} or a heredoc. That
	 * shape cannot be validated as written: the script text is not present anywhere in the
	 * command line for this validator to inspect (unlike {@code sh -c "<script>"}, which {@link
	 * #shellDashCScript} already recurses into). Returns null when the interpreter has a
	 * positional argument -- a script file path, e.g. {@code bash scripts/verify-foo.sh} -- since
	 * that is a deliberately supported, trusted use of a post-completion command (see {@link
	 * PostCompletionCommandRule}'s javadoc) that this validator cannot and does not attempt to
	 * inspect the contents of.
	 */
	private String bareShellInterpreterViolation(List<String> tokens) {
		if (tokens.isEmpty() || !SHELL_INTERPRETERS.contains(baseName(tokens.get(0)))) {
			return null;
		}
		for (int i = 1; i < tokens.size(); i++) {
			if (!tokens.get(i).startsWith("-")) {
				return null;
			}
		}
		return "Shell interpreter invoked with no script file or -c argument in \""
				+ String.join(" ", tokens) + "\" reads its script from standard input (e.g. via "
				+ "a pipe or heredoc), which cannot be validated as written. Run mvn/pytest "
				+ "directly, or invoke an explicit script file instead of piping one into the "
				+ "interpreter.";
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

	/**
	 * Splits {@code text} on whitespace and shell operators, honouring quotes, unquoted
	 * backslash escapes (e.g. an argument written {@code mv\n} becomes the single word
	 * {@code mvn} once the escaping backslash is removed, matching what {@code sh -c} actually
	 * executes), and {@code $(...)}/backtick command substitutions -- the latter
	 * captured as part of the surrounding token verbatim (including their delimiters) rather
	 * than split apart by the bare {@code (}/{@code )} entries in {@link #SHELL_OPERATORS},
	 * which would otherwise scatter a substitution's own parens and interior words across
	 * unrelated segments and hide a substitution occupying command position from
	 * {@link #isSubstitutionExecutable}.
	 *
	 * <p>An unquoted {@code #} that begins a word (i.e. at the start or right after whitespace or
	 * a shell operator) starts a comment and ends the line, matching the shell: without this,
	 * {@code mvn test # -Dtest=Foo#bar} would tokenize the commented-out selector as a live
	 * {@code -Dtest} argument and {@link #mavenSegmentViolation} would wrongly treat the broad
	 * {@code mvn test} as narrow. A {@code #} in the middle of a word ({@code FooTest#testBar}) is
	 * preserved as an ordinary character, since the shell does not start a comment there.</p>
	 */
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
					i++;
					continue;
				}
				if (c == '\\' && i + 1 < n) {
					current.append(text.charAt(i + 1));
					i += 2;
					continue;
				}
				current.append(c);
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
			if (c == '\\') {
				if (i + 1 < n) {
					current.append(text.charAt(i + 1));
					haveToken = true;
				}
				i += 2;
				continue;
			}
			if (c == '$' && i + 1 < n && text.charAt(i + 1) == '(') {
				int end = balancedParenEnd(text, i + 2);
				current.append(text, i, end);
				haveToken = true;
				i = end;
				continue;
			}
			if (c == '`') {
				int close = text.indexOf('`', i + 1);
				int end = close < 0 ? n : close + 1;
				current.append(text, i, end);
				haveToken = true;
				i = end;
				continue;
			}
			if (c == '#' && !haveToken) {
				break;
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

	/**
	 * Returns the index just past the closing parenthesis that balances the depth-1 open paren
	 * whose contents start at {@code start} (i.e. the character immediately after a {@code $(}),
	 * honouring nested parens so a {@code $( ... $(...) ... )} is not truncated at the first
	 * closing paren. Returns {@code text.length()} when the parens are unbalanced, so an
	 * unterminated substitution still consumes the rest of the text as one token instead of
	 * leaving a stray {@code $(} for {@link #SHELL_OPERATORS} to split on.
	 */
	private static int balancedParenEnd(String text, int start) {
		int depth = 1;
		int j = start;
		int n = text.length();
		while (j < n && depth > 0) {
			char c = text.charAt(j);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			}
			j++;
		}
		return j;
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
