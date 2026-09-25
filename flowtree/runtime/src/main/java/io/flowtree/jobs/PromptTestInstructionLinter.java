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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a job's free-text prompt for English-language instructions to run a broad test set,
 * mirroring {@code tools/mcp/manager/execution_limits.py}'s
 * {@code lint_prompt_for_broad_test_instructions}; keep the two in sync.
 *
 * <p>{@code ar-manager}'s {@code workstream_submit_task} applies the Python version to every
 * prompt it accepts, but a caller that posts to the controller's {@code /api/submit} directly
 * has no other path through that check -- the controller only validated {@code command} and
 * {@code postCompletionCommand} (see {@link PostCompletionCommandValidator}) and never the
 * prompt text itself, so a direct submission could tell the agent, in English, to "run the full
 * test suite" and bypass the rule entirely. This class closes that gap on the controller side.
 * There is no bypass for this check.</p>
 */
public class PromptTestInstructionLinter {

	/** Matches a "full/whole/entire test suite" phrase. */
	private static final Pattern FULL_SUITE = Pattern.compile(
			"\\b(full|whole|entire)\\s+test\\s+suite\\b", Pattern.CASE_INSENSITIVE);

	/** Matches a "run all/every test(s)" phrase. */
	private static final Pattern RUN_ALL_TESTS = Pattern.compile(
			"\\brun\\s+(all|every)\\s+(of\\s+the\\s+)?tests?\\b", Pattern.CASE_INSENSITIVE);

	/** Matches a "run the ... module tests" phrase (a whole module's test run). */
	private static final Pattern MODULE_TESTS = Pattern.compile(
			"\\b(?:run|execute|test)\\s+(?:the\\s+)?(?:relevant\\s+)?[\\w./-]*\\s*module(?:'s)?\\s+tests?\\b",
			Pattern.CASE_INSENSITIVE);

	/** Matches the reversed-word-order form of {@link #MODULE_TESTS}: "run the tests for the
	 * engine/utils module", "run tests in the module". Plural "tests" only -- "run the test in
	 * module X" names a single test, not a suite. */
	private static final Pattern TESTS_FOR_MODULE = Pattern.compile(
			"\\b(?:run|execute)\\s+(?:the\\s+)?(?:relevant\\s+)?tests\\s+(?:for|in|of|from)\\s+"
					+ "(?:the\\s+)?[\\w./-]*\\s*module\\b",
			Pattern.CASE_INSENSITIVE);

	/** Matches a "run(ning) ... shard" phrase. */
	private static final Pattern SHARD = Pattern.compile(
			"\\brun(?:ning)?\\s+(?:the\\s+)?[\\w./-]*\\s*(?:CI\\s+)?shard\\b", Pattern.CASE_INSENSITIVE);

	/** Matches an AR_TEST_GROUP/AR_TEST_GROUPS reference anywhere in a prompt line. */
	private static final Pattern AR_TEST_GROUP = Pattern.compile("AR_TEST_GROUPS?\\b");

	/** Splits a prompt line on the same chain operators a shell would, by plain text rather
	 * than full tokenization -- prompt lines are English prose and commonly contain unescaped
	 * apostrophes ({@code don't}, {@code module's}) that a quote-aware tokenizer would choke on. */
	private static final Pattern CHAIN_SPLIT = Pattern.compile("&&|\\|\\||;|\\|");

	/** Matches an {@code mvn}/{@code mvnw}/... launcher name (see
	 * {@link PostCompletionCommandValidator#MVN_LAUNCHER_NAMES}), anywhere in a prompt fragment.
	 * Matched independently of {@link #MVN_TEST_PHASE_PATTERN} rather than as one combined
	 * regex requiring the phase immediately after the launcher -- a prose fragment such as
	 * "run mvn -pl engine/utils test" or "run mvn clean test" has other words between the
	 * launcher and the phase, exactly like {@code mavenSegmentViolation}'s argument-aware scan
	 * of every tokenized argument in {@link PostCompletionCommandValidator} (it does not
	 * require the phase to be the first argument either). */
	private static final Pattern MVN_LAUNCHER_PATTERN = Pattern.compile(
			"\\b(?:" + alternation(PostCompletionCommandValidator.MVN_LAUNCHER_NAMES) + ")\\b",
			Pattern.CASE_INSENSITIVE);

	/** Matches a test-running Maven phase (see
	 * {@link PostCompletionCommandValidator#TEST_RUNNING_PHASES}), anywhere in a prompt
	 * fragment. See {@link #MVN_LAUNCHER_PATTERN} for why this is matched independently. */
	private static final Pattern MVN_TEST_PHASE_PATTERN = Pattern.compile(
			"\\b(?:" + alternation(PostCompletionCommandValidator.TEST_RUNNING_PHASES) + ")\\b",
			Pattern.CASE_INSENSITIVE);

	/** The Maven default-lifecycle phases (plus the {@code clean} and {@code site} lifecycles) a
	 * real command split across lines can begin its continuation with -- e.g. "Run mvn" followed
	 * by "clean install -pl engine/utils" continues onto a line starting with "clean". Used by
	 * {@link #opensWithMavenArgument(String)} to tell a command continuation apart from an ordinary
	 * prose sentence that merely follows a line mentioning "mvn"; a prose line continues with a word
	 * like "to"/"and"/"then", never with a lifecycle phase or a flag. Broader than
	 * {@link PostCompletionCommandValidator#TEST_RUNNING_PHASES} on purpose: the FIRST token of the
	 * continuation may be a non-test phase ("clean") with the test-running phase ("install") later
	 * on the same line. Mirrors {@code _MVN_LIFECYCLE_PHASES} in {@code execution_limits.py}. */
	private static final Set<String> MVN_LIFECYCLE_PHASES = new HashSet<>(Arrays.asList(
			"pre-clean", "clean", "post-clean",
			"validate", "initialize", "generate-sources", "process-sources",
			"generate-resources", "process-resources", "compile", "process-classes",
			"generate-test-sources", "process-test-sources", "generate-test-resources",
			"process-test-resources", "test-compile", "process-test-classes", "test",
			"prepare-package", "package", "pre-integration-test", "integration-test",
			"post-integration-test", "verify", "install", "deploy",
			"pre-site", "site", "post-site", "site-deploy"));

	/** Matches an explicit {@code -Dtest=Class#method}-shaped mention in a prompt fragment. */
	private static final Pattern SELECTOR_PATTERN = Pattern.compile(
			"-Dtest=\\S+#\\S+", Pattern.CASE_INSENSITIVE);

	/** Matches a whole {@code -DskipTests}/{@code -Dmaven.test.skip} mention, capturing the
	 * assigned value token when present -- a bare flag with no {@code =value} means {@code true}.
	 * The value is captured as a non-space run (not just {@code true}/{@code false}) so a dynamic
	 * value like {@code -DskipTests=$(printf false)} or {@code -DskipTests=$SKIP} is seen as an
	 * occurrence and classified as non-skipping by
	 * {@link PostCompletionCommandValidator#classifySkipValue(String)} rather than leaving
	 * the bare {@code -DskipTests} prefix to read as true. Scanned with {@link Matcher#find()}
	 * across free prose rather than {@link Matcher#matches()} against a single token, since a
	 * prompt line is not pre-tokenized; {@link #effectiveSkipValue} still resolves multiple
	 * mentions in the same fragment to the LAST one's value, matching real Maven {@code -D}
	 * semantics. */
	private static final Pattern SKIP_TESTS_MENTION = Pattern.compile(
			"-DskipTests(?:=(\\S+)|\\b)", Pattern.CASE_INSENSITIVE);

	/** Same shape as {@link #SKIP_TESTS_MENTION} for the {@code maven.test.skip} property. The
	 * no-value form ends at a property boundary ({@code \b}) so the bare {@code -DskipTests}/{@code
	 * -Dmaven.test.skip} prefix is not read out of a longer, unrelated property such as
	 * {@code -DskipTestsFoo} -- Maven treats that as a distinct property and still runs tests, so
	 * {@code mvn verify -DskipTestsFoo} must not be exempted as build-only. */
	private static final Pattern MAVEN_TEST_SKIP_MENTION = Pattern.compile(
			"-Dmaven\\.test\\.skip(?:=(\\S+)|\\b)", Pattern.CASE_INSENSITIVE);

	/** Sentence-ending punctuation stripped from a skip value seen in free prose before it is
	 * classified, so "-DskipTests=true." at the end of a sentence still reads as the boolean true
	 * in the prompt path. This tolerance is deliberately confined to the prompt linter: a real
	 * command token is classified exactly by
	 * {@link PostCompletionCommandValidator#classifySkipValue(String)}. */
	private static final Pattern TRAILING_PROSE_PUNCTUATION = Pattern.compile("[.,;:!?]+$");

	/** Matches a {@code -Dtest=<value>} mention, capturing its value. */
	private static final Pattern DTEST_VALUE = Pattern.compile("-Dtest=(\\S+)", Pattern.CASE_INSENSITIVE);

	/** Matches a {@code python}/{@code python3 -m unittest} mention in a prompt fragment.
	 * Interpreter option flags are allowed between the interpreter and {@code -m} -- mirroring
	 * {@link #PYTEST_MODULE_INVOCATION} and the command-side {@code _index_of_module_flag} -- so
	 * that {@code python3 -O -m unittest discover} is still recognized rather than waved through
	 * because {@code -O} breaks the literal {@code python3 -m} sequence. */
	private static final Pattern UNITTEST_MENTION = Pattern.compile(
			"\\bpython(?:\\d+(?:\\.\\d+)*)?(?:\\s+-\\S+)*\\s+-m\\s+unittest\\b", Pattern.CASE_INSENSITIVE);

	/** Matches the word "discover", as in {@code python -m unittest discover}. */
	private static final Pattern DISCOVER = Pattern.compile("\\bdiscover\\b", Pattern.CASE_INSENSITIVE);

	/** Matches a dotted identifier with at least two dots, e.g. {@code module.Class.method}. */
	private static final Pattern DOTTED_ID = Pattern.compile("\\b\\w+(?:\\.\\w+){2,}\\b");

	/** Matches a {@code python}/{@code python3 -m pytest} invocation, which is always a command. */
	private static final Pattern PYTEST_MODULE_INVOCATION = Pattern.compile(
			"\\bpython(?:\\d+(?:\\.\\d+)*)?(?:\\s+-\\S+)*\\s+-m\\s+pytest\\b", Pattern.CASE_INSENSITIVE);

	/** Matches a bare {@code pytest}/{@code py.test} word, capturing an optional preceding run
	 * verb so {@link #pytestWithoutSingleNodeId} can tell an instruction to run it from prose that
	 * merely names the tool. The end of the match is the start of the invocation's arguments. */
	private static final Pattern PYTEST_BARE_INVOCATION = Pattern.compile(
			"(?<![\\w.-])(?:(run|execute)\\s+)?py\\.?test\\b(?![.-]\\w)",
			Pattern.CASE_INSENSITIVE);

	/** Matches an argument-shaped token: a flag, a path, a {@code .py} file or a node id. */
	private static final Pattern PYTEST_ARGUMENT_SHAPE = Pattern.compile("^-|/|\\.py\\b|::");

	/** One rule: a line-level predicate paired with the human-readable reason to report when it
	 * matches. Mirrors the Python prompt linter's {@code _TEST_LINT_PATTERNS} list, where each
	 * entry either a compiled regex or a small matcher class exposing the same {@code search}
	 * shape -- expressed here as a lambda against {@link LineRule} instead, since Java has no
	 * single type that is both "a compiled pattern" and "a custom matcher". */
	private static final List<LineRule> RULES = buildRules();

	/** Builds {@link #RULES} in the order they are checked against each prompt line; the first
	 * rule to match a line wins for that line. */
	private static List<LineRule> buildRules() {
		List<LineRule> rules = new ArrayList<>();
		rules.add(new LineRule(line -> FULL_SUITE.matcher(line).find(),
				"\"full/whole/entire test suite\" phrase"));
		rules.add(new LineRule(line -> RUN_ALL_TESTS.matcher(line).find(),
				"\"run all/every test(s)\" phrase"));
		rules.add(new LineRule(line -> MODULE_TESTS.matcher(line).find(),
				"\"run the ... module tests\" phrase (a whole module's test run)"));
		rules.add(new LineRule(line -> TESTS_FOR_MODULE.matcher(line).find(),
				"\"run the tests for/in the ... module\" phrase (a whole module's test run)"));
		rules.add(new LineRule(line -> SHARD.matcher(line).find(),
				"\"run(ning) ... shard\" phrase"));
		rules.add(new LineRule(line -> AR_TEST_GROUP.matcher(line).find(),
				"AR_TEST_GROUP/AR_TEST_GROUPS reference"));
		rules.add(new LineRule(PromptTestInstructionLinter::mvnSegmentWithoutSelector,
				"\"mvn test/verify/install/package/deploy\" without a Class#method -Dtest selector"));
		rules.add(new LineRule(PromptTestInstructionLinter::dtestBroadValue,
				"-Dtest=<value> not naming exactly one Class#method entry"));
		rules.add(new LineRule(PromptTestInstructionLinter::unittestDiscovery,
				"\"python -m unittest discover\" (or a unittest invocation naming no single "
						+ "module.Class.method id)"));
		rules.add(new LineRule(PromptTestInstructionLinter::pytestWithoutSingleNodeId,
				"pytest invocation not naming exactly one file.py::test_name node id"));
		return rules;
	}

	/** Renders {@code values} as a regex alternation of literally-quoted entries, sorted for
	 * determinism. */
	private static String alternation(List<String> values) {
		List<String> sorted = new ArrayList<>(values);
		sorted.sort(null);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < sorted.size(); i++) {
			if (i > 0) {
				sb.append('|');
			}
			sb.append(Pattern.quote(sorted.get(i)));
		}
		return sb.toString();
	}

	/**
	 * Flags an {@code mvn <test-running-phase>} mention whose OWN chained-command fragment has
	 * no Class#method {@code -Dtest} selector and no effective skip flag, without being fooled
	 * by a selector or skip flag belonging to a different command earlier or later on the same
	 * line -- e.g. {@code mvn test && mvn test -Dtest=Foo#bar} must not exempt the first, broad
	 * {@code mvn test} just because a selector exists later on the line for an unrelated chained
	 * command. Also resolves {@link #effectiveSkipValue} per fragment so a later {@code
	 * -DskipTests=false} correctly overrides an earlier {@code -DskipTests=true} mention instead
	 * of the mere presence of "true" anywhere in the fragment being read as sufficient.
	 */
	private static boolean mvnSegmentWithoutSelector(String line) {
		for (String fragment : CHAIN_SPLIT.split(line)) {
			if (!MVN_LAUNCHER_PATTERN.matcher(fragment).find()
					|| !MVN_TEST_PHASE_PATTERN.matcher(fragment).find()) {
				continue;
			}
			if (SELECTOR_PATTERN.matcher(fragment).find()) {
				continue;
			}
			if (Boolean.TRUE.equals(effectiveSkipValue(fragment, SKIP_TESTS_MENTION))
					|| Boolean.TRUE.equals(effectiveSkipValue(fragment, MAVEN_TEST_SKIP_MENTION))) {
				continue;
			}
			return true;
		}
		return false;
	}

	/** Returns the effective boolean value of a Maven skip-property mention in free prose,
	 * taking the LAST occurrence of {@code pattern} in {@code fragment} -- mirroring
	 * {@code PostCompletionCommandValidator}'s last-{@code -D}-wins Maven semantics for an
	 * already-tokenized args list, but scanned across prose text instead. Returns {@code null}
	 * when the property is never mentioned. A dynamic last value (command substitution or
	 * parameter expansion) is classified as non-skipping, so a prompt such as
	 * {@code mvn verify -DskipTests=true -DskipTests=$(printf false)} is not treated as
	 * build-only. Delegates value classification to
	 * {@link PostCompletionCommandValidator#classifySkipValue(String)} so the prompt linter and
	 * the command validator apply an identical rule. */
	private static Boolean effectiveSkipValue(String fragment, Pattern pattern) {
		Boolean value = null;
		Matcher matcher = pattern.matcher(fragment);
		while (matcher.find()) {
			String captured = matcher.group(1);
			if (captured != null) {
				captured = TRAILING_PROSE_PUNCTUATION.matcher(captured).replaceAll("");
			}
			value = PostCompletionCommandValidator.classifySkipValue(captured);
		}
		return value;
	}

	/**
	 * Flags a {@code -Dtest=<value>} mention that does not name exactly one wildcard-free
	 * {@code Class#method} entry, checking EVERY {@code -Dtest=} occurrence on the line rather
	 * than only the first -- e.g. {@code mvn test -Dtest=Foo#bar -Dtest=WholeClass} must still be
	 * flagged even though the first value alone would be narrow enough, since a later occurrence
	 * overrides it and Maven ultimately runs the whole class.
	 */
	private static boolean dtestBroadValue(String line) {
		Matcher matcher = DTEST_VALUE.matcher(line);
		while (matcher.find()) {
			if (!PostCompletionCommandValidator.dtestIsNarrow(matcher.group(1))) {
				return true;
			}
		}
		return false;
	}

	/** Flags a {@code python -m unittest}/{@code python3 -m unittest} mention that either uses
	 * {@code discover} or does not name EXACTLY ONE dotted {@code module.Class.method} test id --
	 * mirroring {@link PostCompletionCommandValidator#unittestSegmentViolation}'s "exactly one
	 * positional" rule, scanned across prose text instead of a tokenized argument list. A bare
	 * {@code find()} that only checks whether a dotted id is present anywhere in the fragment
	 * would accept a prompt naming two dotted ids (e.g. {@code python -m unittest
	 * foo.Bar.test_a bar.Baz.test_b}), which still runs both tests in one invocation. */
	private static boolean unittestDiscovery(String line) {
		for (String fragment : CHAIN_SPLIT.split(line)) {
			if (!UNITTEST_MENTION.matcher(fragment).find()) {
				continue;
			}
			int dottedIdCount = 0;
			Matcher dottedIdMatcher = DOTTED_ID.matcher(fragment);
			while (dottedIdMatcher.find()) {
				dottedIdCount++;
			}
			if (DISCOVER.matcher(fragment).find() || dottedIdCount != 1) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Flags a {@code pytest}/{@code py.test}/{@code python -m pytest} invocation whose chained-command
	 * fragment does not name EXACTLY ONE {@code file.py::test_name} node id, mirroring
	 * {@link PostCompletionCommandValidator}'s pytest rule for free-text prompt instructions.
	 * Without this, a prompt such as "Run pytest tools/mcp/manager" was accepted when the
	 * submission carried no command field for the command validator to inspect.
	 *
	 * <p>{@code python -m pytest} is always an invocation. A bare {@code pytest} word is only
	 * treated as one when a run verb precedes it or an argument-shaped token follows it, so prose
	 * that merely names the tool -- "add a pytest regression test" -- is not flagged. Counting only
	 * {@code ::} tokens across the whole fragment would miss a bare positional next to a node id
	 * (e.g. {@code pytest tests/ test_foo.py::test_bar}, which still runs the whole {@code tests/}
	 * directory), so the arguments are read as the command validator reads them.</p>
	 */
	private static boolean pytestWithoutSingleNodeId(String line) {
		for (String fragment : CHAIN_SPLIT.split(line.replace('`', ' '))) {
			Matcher module = PYTEST_MODULE_INVOCATION.matcher(fragment);
			while (module.find()) {
				if (pytestArgumentsAreBroad(argumentsAfter(fragment, module.end()))) {
					return true;
				}
			}
			Matcher bare = PYTEST_BARE_INVOCATION.matcher(fragment);
			while (bare.find()) {
				List<String> args = argumentsAfter(fragment, bare.end());
				if (bare.group(1) == null && args.isEmpty()) {
					continue;
				}
				if (pytestArgumentsAreBroad(args)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The contiguous run of argument-shaped tokens (flags, paths, {@code .py} files or node ids)
	 * that immediately follows the invocation word -- pytest's own positional/option arguments.
	 * Scanning stops at the first prose token so surrounding sentence text is not mistaken for an
	 * argument (e.g. "... test_foo.py::test_bar to verify the fix").
	 *
	 * @param fragment the chained-command fragment being scanned
	 * @param start    the index just past the matched {@code pytest} invocation word
	 * @return the argument tokens, in order; empty when none follow
	 */
	private static List<String> argumentsAfter(String fragment, int start) {
		List<String> args = new ArrayList<>();
		String remainder = fragment.substring(start).trim();
		if (remainder.isEmpty()) {
			return args;
		}
		for (String token : remainder.split("\\s+")) {
			if (token.startsWith("-") || PYTEST_ARGUMENT_SHAPE.matcher(token).find()) {
				args.add(token);
			} else {
				break;
			}
		}
		return args;
	}

	/**
	 * Mirrors {@link PostCompletionCommandValidator}'s pytest rule: the invocation is broad unless
	 * its arguments name exactly one positional and that positional is a {@code ::} node id. A bare
	 * positional (a whole file or directory) or more than one positional runs more than one test.
	 *
	 * @param args the invocation's argument tokens, from {@link #argumentsAfter}
	 * @return true when the arguments describe a broad run
	 */
	private static boolean pytestArgumentsAreBroad(List<String> args) {
		int positionals = 0;
		String onlyPositional = null;
		for (String arg : args) {
			if (!arg.startsWith("-")) {
				positionals++;
				onlyPositional = arg;
			}
		}
		return positionals != 1 || !onlyPositional.contains("::");
	}

	/** The prompt being linted. */
	private final String prompt;

	/** Violations found by {@link #lint()}, in the order they were discovered. */
	private final List<String> violations = new ArrayList<>();

	/**
	 * Creates a linter for the given prompt; call {@link #lint()} to run it.
	 *
	 * @param prompt the free-text task prompt to scan, possibly null or blank
	 */
	public PromptTestInstructionLinter(String prompt) {
		this.prompt = prompt;
	}

	/** Runs the checks, populating {@link #getViolations()}, and returns this instance. A line
	 * whose command continues onto the next (see {@link #continuesOntoNextLine}) is also linted
	 * joined with its continuation lines, and the hit is reported at the line the command starts
	 * on. There is no bypass flag for this linter; a prompt legitimately quoting a forbidden
	 * phrase must be rewritten instead. */
	public PromptTestInstructionLinter lint() {
		if (prompt == null || prompt.trim().isEmpty()) {
			return this;
		}
		String[] lines = prompt.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			String reason = firstHit(line);
			if (reason == null) {
				String joined = joinedContinuation(lines, i);
				if (joined != null) {
					reason = firstHit(joined);
				}
			}
			if (reason != null) {
				violations.add("Line " + (i + 1) + ": " + reason
						+ "\n    > " + truncate(line.trim(), 120));
			}
		}
		return this;
	}

	/** Returns the reason of the first rule in {@link #RULES} that matches {@code text}, or
	 * null when none does. */
	private static String firstHit(String text) {
		for (LineRule rule : RULES) {
			if (rule.matcher.matches(text)) {
				return rule.reason;
			}
		}
		return null;
	}

	/**
	 * Whether {@code nextLine} opens with an argument-shaped token -- a flag ({@code -pl},
	 * {@code -DskipTests}) or a Maven lifecycle phase as its first word ({@code clean},
	 * {@code install}). A command split across lines continues with one of these; an ordinary
	 * prose sentence following a line that merely mentions {@code mvn} continues with a word like
	 * "to"/"and"/"then" and must NOT be joined.
	 */
	private static boolean opensWithMavenArgument(String nextLine) {
		String stripped = nextLine.trim();
		if (stripped.isEmpty()) {
			return false;
		}
		String first = stripped.split("\\s+", 2)[0];
		return first.startsWith("-") || MVN_LIFECYCLE_PHASES.contains(first.toLowerCase());
	}

	/**
	 * Whether a command in {@code text} continues onto {@code nextLine}: the text ends with a
	 * shell {@code \} continuation, or its last chained fragment names a Maven launcher but no
	 * lifecycle phase yet AND {@code nextLine} opens with an argument-shaped token ("Run mvn"
	 * followed by "clean install -pl engine/utils"). The {@code nextLine} guard keeps prose that
	 * merely mentions a launcher without a phase -- "We build with mvn." followed by "Then verify
	 * the fix." -- from being joined into a fabricated "mvn ... verify" command and falsely
	 * flagged. pytest and unittest need no joining -- an invocation left with no target on its own
	 * line is already flagged as broad.
	 */
	private static boolean continuesOntoNextLine(String text, String nextLine) {
		if (text.trim().endsWith("\\")) {
			return true;
		}
		String[] fragments = CHAIN_SPLIT.split(text, -1);
		String last = fragments[fragments.length - 1];
		if (!MVN_LAUNCHER_PATTERN.matcher(last).find() || MVN_TEST_PHASE_PATTERN.matcher(last).find()) {
			return false;
		}
		return opensWithMavenArgument(nextLine);
	}

	/**
	 * Joins {@code lines[index]} with the lines its command continues onto (see
	 * {@link #continuesOntoNextLine}), stopping at a blank line or the end of the prompt, so a
	 * command split across lines is linted as one.
	 *
	 * @return the joined text, or null when the line does not continue
	 */
	private static String joinedContinuation(String[] lines, int index) {
		String text = lines[index];
		int next = index + 1;
		while (next < lines.length && !lines[next].trim().isEmpty()
				&& continuesOntoNextLine(text, lines[next])) {
			String trimmed = text.trim();
			if (trimmed.endsWith("\\")) {
				trimmed = trimmed.substring(0, trimmed.length() - 1);
			}
			text = trimmed + " " + lines[next].trim();
			next++;
		}
		return next > index + 1 ? text : null;
	}

	/** Returns the violations found by {@link #lint()}; empty until called. */
	public List<String> getViolations() {
		return violations;
	}

	/** Returns true when {@link #lint()} found at least one violation. */
	public boolean hasViolations() {
		return !violations.isEmpty();
	}

	/** Renders {@link #getViolations()} as a rejection message for the submitter. */
	public String formatRejection() {
		StringBuilder sb = new StringBuilder(
				"Prompt instructs the agent to run a broad test set. Agents may run at most one "
				+ "narrowly-selected test per invocation; broad verification (full suites, module "
				+ "suites, CI shards) belongs to CI only. There is no bypass for this check.\n\n"
				+ "Forbidden phrases found:\n");
		for (String violation : violations) {
			sb.append("  ").append(violation).append('\n');
		}
		sb.append("\nRewrite the prompt to name the specific failing test(s) to run, one at a time.");
		return sb.toString();
	}

	/** Truncates {@code s} to at most {@code max} characters. */
	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	/** A single line-level predicate, matched against every line of the prompt in
	 * {@link #RULES} order; the first rule to match a line wins for that line. */
	private static final class LineRule {

		/** The predicate a prompt line must satisfy for this rule to fire. */
		private final LinePredicate matcher;

		/** The human-readable reason reported when {@link #matcher} fires. */
		private final String reason;

		/** Creates a rule pairing {@code matcher} with the {@code reason} to report on match. */
		private LineRule(LinePredicate matcher, String reason) {
			this.matcher = matcher;
			this.reason = reason;
		}
	}

	/** A predicate over a single prompt line, used to keep {@link #RULES} uniform across both
	 * simple compiled-pattern checks and the segment-aware checks that need their own logic. */
	private interface LinePredicate {
		/** Returns true when {@code line} satisfies this predicate. */
		boolean matches(String line);
	}
}
