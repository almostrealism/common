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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a job's free-text prompt for English-language instructions to run a broad test set,
 * mirroring {@code tools/mcp/manager/test_execution_limits.py}'s
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

	/** Matches an explicit {@code -Dtest=Class#method}-shaped mention in a prompt fragment. */
	private static final Pattern SELECTOR_PATTERN = Pattern.compile(
			"-Dtest=\\S+#\\S+", Pattern.CASE_INSENSITIVE);

	/** Matches a whole {@code -DskipTests}/{@code -Dmaven.test.skip} mention, capturing an
	 * explicit {@code true}/{@code false} value when present -- a bare flag with no
	 * {@code =value} means {@code true}. Scanned with {@link Matcher#find()} across free prose
	 * rather than {@link Matcher#matches()} against a single token, since a prompt line is not
	 * pre-tokenized; {@link #effectiveSkipValue} still resolves multiple mentions in the same
	 * fragment to the LAST one's value, matching real Maven {@code -D} semantics. */
	private static final Pattern SKIP_TESTS_MENTION = Pattern.compile(
			"-DskipTests(?:=(true|false)\\b)?", Pattern.CASE_INSENSITIVE);

	/** Same shape as {@link #SKIP_TESTS_MENTION} for the {@code maven.test.skip} property. */
	private static final Pattern MAVEN_TEST_SKIP_MENTION = Pattern.compile(
			"-Dmaven\\.test\\.skip(?:=(true|false)\\b)?", Pattern.CASE_INSENSITIVE);

	/** Matches a {@code -Dtest=<value>} mention, capturing its value. */
	private static final Pattern DTEST_VALUE = Pattern.compile("-Dtest=(\\S+)", Pattern.CASE_INSENSITIVE);

	/** Matches a {@code python}/{@code python3 -m unittest} mention in a prompt fragment. */
	private static final Pattern UNITTEST_MENTION = Pattern.compile(
			"\\bpython3?\\s+-m\\s+unittest\\b", Pattern.CASE_INSENSITIVE);

	/** Matches the word "discover", as in {@code python -m unittest discover}. */
	private static final Pattern DISCOVER = Pattern.compile("\\bdiscover\\b", Pattern.CASE_INSENSITIVE);

	/** Matches a dotted identifier with at least two dots, e.g. {@code module.Class.method}. */
	private static final Pattern DOTTED_ID = Pattern.compile("\\b\\w+(?:\\.\\w+){2,}\\b");

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
	 * when the property is never mentioned. */
	private static Boolean effectiveSkipValue(String fragment, Pattern pattern) {
		Boolean value = null;
		Matcher matcher = pattern.matcher(fragment);
		while (matcher.find()) {
			String explicit = matcher.group(1);
			value = explicit == null || "true".equalsIgnoreCase(explicit);
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
	 * {@code discover} or names no single dotted {@code module.Class.method} test id. */
	private static boolean unittestDiscovery(String line) {
		for (String fragment : CHAIN_SPLIT.split(line)) {
			if (!UNITTEST_MENTION.matcher(fragment).find()) {
				continue;
			}
			if (DISCOVER.matcher(fragment).find() || !DOTTED_ID.matcher(fragment).find()) {
				return true;
			}
		}
		return false;
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

	/** Runs the checks, populating {@link #getViolations()}, and returns this instance. There
	 * is no bypass flag for this linter; a prompt legitimately quoting a forbidden phrase must
	 * be rewritten instead. */
	public PromptTestInstructionLinter lint() {
		if (prompt == null || prompt.trim().isEmpty()) {
			return this;
		}
		String[] lines = prompt.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			for (LineRule rule : RULES) {
				if (rule.matcher.matches(line)) {
					violations.add("Line " + (i + 1) + ": " + rule.reason
							+ "\n    > " + truncate(line.trim(), 120));
					break;
				}
			}
		}
		return this;
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
