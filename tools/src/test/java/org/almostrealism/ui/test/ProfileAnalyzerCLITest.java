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

package org.almostrealism.ui.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.io.Console;
import org.almostrealism.ui.ProfileAnalyzerCLI;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Tests for {@link ProfileAnalyzerCLI}, the JSON command-line front end used by the
 * profile-analyzer MCP server.
 *
 * <p>Each test saves the deterministic profile from {@link OperationProfileTestBase}
 * to a temporary XML file, invokes {@link ProfileAnalyzerCLI#main(String[])} on it,
 * captures the JSON document printed to the root {@link Console}, parses it with
 * Jackson (which also proves the output is well-formed JSON), and asserts on the
 * computed values.</p>
 */
public class ProfileAnalyzerCLITest extends OperationProfileTestBase {
	/** Matches an optional console timestamp prefix followed by the start of a JSON object. */
	private static final Pattern JSON_LINE = Pattern.compile("^(\\[[^\\]]*\\] )?\\{", Pattern.DOTALL);

	/** Parser for the CLI output. */
	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Saves the sample profile to a temporary file.
	 *
	 * @return the absolute path of the saved profile
	 * @throws IOException if the profile cannot be written
	 */
	private String saveSampleProfile() throws IOException {
		File file = Files.createTempFile("profile-analyzer-cli", ".xml").toFile();
		file.deleteOnExit();
		sampleProfile().save(file);
		return file.getAbsolutePath();
	}

	/**
	 * Runs the CLI with the given arguments and parses the JSON document it prints.
	 *
	 * <p>Invokes {@link ProfileAnalyzerCLI#run(String...)} rather than
	 * {@link ProfileAnalyzerCLI#main(String[])} so that a failing command returns an
	 * exit code instead of calling {@link System#exit(int)} and tearing down the
	 * Surefire fork.</p>
	 *
	 * @param args the CLI arguments
	 * @return the parsed JSON output
	 * @throws IOException if the output is not valid JSON
	 */
	private JsonNode run(String... args) throws IOException {
		List<String> chunks = captureOutput(args);

		for (String chunk : chunks) {
			if (JSON_LINE.matcher(chunk).find()) {
				return mapper.readTree(chunk.substring(chunk.indexOf('{')));
			}
		}

		Assert.fail("No JSON document was printed for " + String.join(" ", args) + ": " + chunks);
		return null;
	}

	/**
	 * Runs the CLI with the given arguments, capturing everything printed to the root
	 * {@link Console}, and returns the exit code reported by {@link ProfileAnalyzerCLI#run(String...)}.
	 *
	 * @param output a list that receives the captured console chunks
	 * @param args   the CLI arguments
	 * @return the CLI exit code ({@code 0} on success, {@code 1} on failure)
	 */
	private int runForExit(List<String> output, String... args) {
		List<String> chunks = captureOutput(args);
		output.addAll(chunks);
		return lastExit;
	}

	/** Exit code reported by the most recent {@link #captureOutput(String...)} invocation. */
	private int lastExit;

	/**
	 * Invokes {@link ProfileAnalyzerCLI#run(String...)}, recording its exit code and
	 * returning the chunks printed to the root {@link Console} during the call.
	 *
	 * @param args the CLI arguments
	 * @return the captured console chunks
	 */
	private List<String> captureOutput(String... args) {
		List<String> chunks = new ArrayList<>();
		Consumer<String> listener = chunks::add;
		Console.root().addListener(listener);

		try {
			lastExit = ProfileAnalyzerCLI.run(args);
		} finally {
			Console.root().removeListener(listener);
		}

		return chunks;
	}

	/**
	 * Finds the element of a JSON array whose {@code key} field equals the given key.
	 *
	 * @param array the JSON array to search
	 * @param key   the key to look for
	 * @return the matching element
	 */
	private static JsonNode entry(JsonNode array, String key) {
		for (JsonNode n : array) {
			if (key.equals(n.get("key").asText())) return n;
		}

		Assert.fail("No entry with key " + key + " in " + array);
		return null;
	}

	/**
	 * The summary command reports the profile name, node count, total measured
	 * duration (preferring measured time over self time), and the top operations
	 * ordered by duration with their metadata detail and share of the total.
	 */
	@Test(timeout = 60000)
	public void summaryReportsTotalsAndTopOperations() throws IOException {
		JsonNode json = run("summary", saveSampleProfile());

		Assert.assertEquals("Root", json.get("name").asText());
		Assert.assertEquals(5, json.get("node_count").asInt());
		// 2.0 (MatMul self) + 0.25 (Inner Add self) + 0.0004 (Softmax measured, preferred over 0.75 self)
		Assert.assertEquals(2.2504, json.get("total_duration_seconds").asDouble(), 1e-9);
		Assert.assertEquals("2.250s", json.get("total_duration_formatted").asText());

		JsonNode top = json.get("top_operations");
		Assert.assertEquals("Nodes with zero duration are excluded", 3, top.size());
		Assert.assertEquals(MATMUL_KEY, top.get(0).get("key").asText());
		Assert.assertEquals(INNER_KEY, top.get(1).get("key").asText());
		Assert.assertEquals(SOFTMAX_KEY, top.get(2).get("key").asText());

		Assert.assertEquals("MatMul (64x64)", top.get(0).get("detail").asText());
		Assert.assertEquals(88.9, top.get(0).get("percentage").asDouble(), 1e-9);
		Assert.assertEquals("2.000s", top.get(0).get("duration_formatted").asText());
		Assert.assertEquals("250.00ms", top.get(1).get("duration_formatted").asText());
		Assert.assertEquals("400.0us", top.get(2).get("duration_formatted").asText());
	}

	/**
	 * The slowest command, with the default category, ranks nodes by effective
	 * duration and derives invocation counts and average durations from the
	 * metric counts.
	 */
	@Test(timeout = 60000)
	public void slowestAllCategoryIncludesInvocationStatistics() throws IOException {
		JsonNode json = run("slowest", saveSampleProfile());

		Assert.assertEquals("all", json.get("category").asText());
		Assert.assertEquals(2.2504, json.get("total_profile_duration").asDouble(), 1e-9);

		JsonNode slowest = json.get("slowest");
		Assert.assertEquals(3, slowest.size());
		Assert.assertEquals(MATMUL_KEY, slowest.get(0).get("key").asText());

		JsonNode matmul = entry(slowest, MATMUL_KEY);
		Assert.assertEquals(4, matmul.get("invocations").asInt());
		Assert.assertEquals(0.5, matmul.get("avg_duration").asDouble(), 1e-9);

		JsonNode softmax = entry(slowest, SOFTMAX_KEY);
		Assert.assertEquals("Measured duration is preferred over self duration",
				0.0004, softmax.get("duration").asDouble(), 1e-9);
		Assert.assertEquals(1, softmax.get("invocations").asInt());
	}

	/**
	 * Filtering by the run category sums only the "run" metric entries, which
	 * changes the ranking, and the limit argument truncates the result.
	 */
	@Test(timeout = 60000)
	public void slowestRunCategoryRanksByRunTimeAndHonorsLimit() throws IOException {
		JsonNode json = run("slowest", saveSampleProfile(), "2", "--category=run");

		Assert.assertEquals("run", json.get("category").asText());
		JsonNode slowest = json.get("slowest");
		Assert.assertEquals(2, slowest.size());
		Assert.assertEquals(MATMUL_KEY, slowest.get(0).get("key").asText());
		Assert.assertEquals(1.5, slowest.get(0).get("duration").asDouble(), 1e-9);
		Assert.assertEquals(3, slowest.get(0).get("invocations").asInt());
		Assert.assertEquals(0.5, slowest.get(0).get("avg_duration").asDouble(), 1e-9);
		Assert.assertEquals("Softmax self time is all run time", SOFTMAX_KEY,
				slowest.get(1).get("key").asText());
		Assert.assertEquals(0.75, slowest.get(1).get("duration").asDouble(), 1e-9);
	}

	/**
	 * Filtering by the compile category includes only nodes with compile entries.
	 */
	@Test(timeout = 60000)
	public void slowestCompileCategoryOnlyIncludesCompiledWork() throws IOException {
		JsonNode json = run("slowest", saveSampleProfile(), "--category=compile");

		JsonNode slowest = json.get("slowest");
		Assert.assertEquals(1, slowest.size());
		Assert.assertEquals(MATMUL_KEY, slowest.get(0).get("key").asText());
		Assert.assertEquals(0.5, slowest.get(0).get("duration").asDouble(), 1e-9);
		Assert.assertEquals(1, slowest.get(0).get("invocations").asInt());
	}

	/**
	 * Without a node key the children command lists the root's children ordered by
	 * total (inclusive) duration, with each child's share of the parent's total.
	 */
	@Test(timeout = 60000)
	public void childrenOfRootAreSortedByTotalDuration() throws IOException {
		JsonNode json = run("children", saveSampleProfile());

		Assert.assertEquals(ROOT_KEY, json.get("parent_key").asText());
		Assert.assertEquals("Root", json.get("parent_name").asText());
		Assert.assertEquals(3, json.get("total_children").asInt());
		Assert.assertEquals(3, json.get("showing").asInt());

		JsonNode children = json.get("children");
		Assert.assertEquals(MATMUL_KEY, children.get(0).get("key").asText());
		Assert.assertEquals(SOFTMAX_KEY, children.get(1).get("key").asText());
		Assert.assertEquals(COPY_KEY, children.get(2).get("key").asText());

		// Inclusive totals: MatMul 2.25, Softmax 0.75, Copy 0 out of 3.0
		Assert.assertEquals(2.25, children.get(0).get("duration").asDouble(), 1e-9);
		Assert.assertEquals(75.0, children.get(0).get("percentage").asDouble(), 1e-9);
		Assert.assertEquals(25.0, children.get(1).get("percentage").asDouble(), 1e-9);
		Assert.assertEquals(0.0, children.get(2).get("percentage").asDouble(), 1e-9);

		Assert.assertTrue(children.get(0).get("has_children").asBoolean());
		Assert.assertFalse(children.get(1).get("has_children").asBoolean());
	}

	/**
	 * The children command resolves a nested node key and reports its children,
	 * and reports an error object for an unknown key.
	 */
	@Test(timeout = 60000)
	public void childrenOfNamedNodeAndUnknownNode() throws IOException {
		String file = saveSampleProfile();

		JsonNode json = run("children", file, MATMUL_KEY);
		Assert.assertEquals("MatMul (64x64)", json.get("parent_detail").asText());
		Assert.assertEquals(1, json.get("total_children").asInt());
		JsonNode child = json.get("children").get(0);
		Assert.assertEquals(INNER_KEY, child.get("key").asText());
		Assert.assertEquals("Add detail", child.get("detail").asText());
		Assert.assertEquals(11.1, child.get("percentage").asDouble(), 1e-9);

		JsonNode missing = run("children", file, MISSING_KEY);
		Assert.assertEquals("Node not found: " + MISSING_KEY, missing.get("error").asText());
	}

	/**
	 * Search matches node names case-insensitively, also matches the metadata
	 * detail, and returns an empty match list when nothing matches.
	 */
	@Test(timeout = 60000)
	public void searchMatchesNamesAndMetadataDetail() throws IOException {
		String file = saveSampleProfile();

		JsonNode byName = run("search", file, "SOFTMAX");
		Assert.assertEquals(1, byName.get("match_count").asInt());
		Assert.assertEquals(SOFTMAX_KEY, byName.get("matches").get(0).get("key").asText());

		JsonNode byDetail = run("search", file, "64x64");
		Assert.assertEquals(1, byDetail.get("match_count").asInt());
		Assert.assertEquals(MATMUL_KEY, byDetail.get("matches").get(0).get("key").asText());

		JsonNode none = run("search", file, "no-such-operation");
		Assert.assertEquals(0, none.get("match_count").asInt());
		Assert.assertEquals(0, none.get("matches").size());
	}

	/**
	 * A search pattern containing JSON metacharacters is escaped in the output so
	 * the document stays parseable and round-trips the original pattern.
	 */
	@Test(timeout = 60000)
	public void searchPatternIsJsonEscaped() throws IOException {
		String pattern = "say \"hi\"\\\tnow";
		JsonNode json = run("search", saveSampleProfile(), pattern);
		Assert.assertEquals(pattern, json.get("pattern").asText());
		Assert.assertEquals(0, json.get("match_count").asInt());
	}

	/**
	 * The breakdown command splits a node's metric entries into compile and run
	 * time by key suffix, sums their counts, includes stage details, and computes
	 * the compile/run percentages.
	 */
	@Test(timeout = 60000)
	public void breakdownSplitsCompileAndRunTime() throws IOException {
		JsonNode json = run("breakdown", saveSampleProfile(), MATMUL_KEY);

		Assert.assertEquals("MatMul", json.get("node_name").asText());
		Assert.assertEquals("MatMul (64x64)", json.get("node_detail").asText());
		Assert.assertEquals(0.5, json.get("compile_time").asDouble(), 1e-9);
		Assert.assertEquals(1, json.get("compile_count").asInt());
		Assert.assertEquals(1.5, json.get("run_time").asDouble(), 1e-9);
		Assert.assertEquals(3, json.get("run_count").asInt());
		Assert.assertEquals(2.0, json.get("total_time").asDouble(), 1e-9);
		Assert.assertEquals("500.00ms", json.get("compile_time_formatted").asText());
		Assert.assertEquals(25.0, json.get("compile_percentage").asDouble(), 1e-9);
		Assert.assertEquals(75.0, json.get("run_percentage").asDouble(), 1e-9);

		JsonNode stages = json.get("stage_details");
		Assert.assertEquals(2, stages.size());
		Assert.assertEquals(0.1, stages.get("parse").asDouble(), 1e-9);
		Assert.assertEquals(0.2, stages.get("codegen").asDouble(), 1e-9);
	}

	/**
	 * A node without any metric reports zero times and zero percentages (no
	 * division by zero), omits stage details, and an unknown key yields an error.
	 */
	@Test(timeout = 60000)
	public void breakdownOfNodeWithoutMetricAndUnknownNode() throws IOException {
		String file = saveSampleProfile();

		JsonNode json = run("breakdown", file, COPY_KEY);
		Assert.assertEquals(0.0, json.get("total_time").asDouble(), 0.0);
		Assert.assertEquals(0, json.get("compile_count").asInt());
		Assert.assertEquals(0.0, json.get("compile_percentage").asDouble(), 0.0);
		Assert.assertEquals(0.0, json.get("run_percentage").asDouble(), 0.0);
		Assert.assertFalse(json.has("stage_details"));

		JsonNode missing = run("breakdown", file, MISSING_KEY);
		Assert.assertEquals("Node not found: " + MISSING_KEY, missing.get("error").asText());
	}

	/**
	 * The source command finds the kernel stored on the root under the node key,
	 * truncates it to the requested number of lines with a marker, and lists the
	 * argument keys paired with their names.
	 */
	@Test(timeout = 60000)
	public void sourceIsTruncatedAndListsArguments() throws IOException {
		JsonNode json = run("source", saveSampleProfile(), MATMUL_KEY, "3");

		Assert.assertEquals(MATMUL_KEY, json.get("source_key").asText());
		Assert.assertEquals(1, json.get("source_count").asInt());

		JsonNode src = json.get("sources").get(0);
		Assert.assertEquals(16, src.get("total_lines").asInt());
		Assert.assertTrue(src.get("truncated").asBoolean());
		Assert.assertEquals(String.join("\n",
						"void f_add_1(double *a) {",
						"for (int i = 0; i < 4; i++) {",
						"a[i] = sin(a[i]) + cos(a[i]);",
						"// ... 13 more lines ..."),
				src.get("source").asText());

		Assert.assertEquals(3, src.get("argument_count").asInt());
		JsonNode args = src.get("arguments");
		Assert.assertEquals(SOFTMAX_KEY, args.get(0).get("key").asText());
		Assert.assertEquals("arg0", args.get(0).get("name").asText());
		Assert.assertEquals(MISSING_KEY, args.get(2).get("key").asText());
		Assert.assertEquals("arg2", args.get(2).get("name").asText());
	}

	/**
	 * With the default line limit, a short source is returned whole and untruncated.
	 */
	@Test(timeout = 60000)
	public void sourceWithDefaultLimitIsNotTruncated() throws IOException {
		JsonNode json = run("source", saveSampleProfile(), MATMUL_KEY);

		JsonNode src = json.get("sources").get(0);
		Assert.assertFalse(src.get("truncated").asBoolean());
		Assert.assertEquals(KERNEL_SOURCE, src.get("source").asText());
	}

	/**
	 * Source lookup tries the node name first: a node named "Inner Add" resolves
	 * to the source registered under that name rather than under its key.
	 */
	@Test(timeout = 60000)
	public void sourceIsMatchedByNodeNameBeforeKey() throws IOException {
		JsonNode json = run("source", saveSampleProfile(), INNER_KEY);

		Assert.assertEquals("Inner Add", json.get("source_key").asText());
		JsonNode src = json.get("sources").get(0);
		Assert.assertEquals("inner code", src.get("source").asText());
		Assert.assertEquals(MATMUL_KEY, src.get("arguments").get(0).get("key").asText());
		Assert.assertEquals("in", src.get("arguments").get(0).get("name").asText());
	}

	/**
	 * When neither the name nor the key matches, a source whose key contains the
	 * node name is used; an empty argument list is reported as zero arguments.
	 */
	@Test(timeout = 60000)
	public void sourceFallsBackToPartialNameMatch() throws IOException {
		JsonNode json = run("source", saveSampleProfile(), SOFTMAX_KEY);

		Assert.assertEquals("Softmax_kernel", json.get("source_key").asText());
		JsonNode src = json.get("sources").get(0);
		Assert.assertEquals(2, src.get("total_lines").asInt());
		Assert.assertEquals(0, src.get("argument_count").asInt());
		Assert.assertEquals(0, src.get("arguments").size());
	}

	/**
	 * A matched source whose text is null is reported as a JSON null rather than
	 * failing.
	 */
	@Test(timeout = 60000)
	public void sourceWithNullTextIsReportedAsNull() throws IOException {
		JsonNode json = run("source", saveSampleProfile(), COPY_KEY);

		Assert.assertEquals(COPY_KEY, json.get("source_key").asText());
		JsonNode src = json.get("sources").get(0);
		Assert.assertTrue(src.get("source").isNull());
		Assert.assertEquals(0, src.get("argument_count").asInt());
	}

	/**
	 * When no source matches the node, the output carries a hint and the sorted
	 * list of available source keys; an unknown node key yields an error.
	 */
	@Test(timeout = 60000)
	public void sourceNotFoundListsAvailableKeys() throws IOException {
		String file = saveSampleProfile();

		JsonNode json = run("source", file, ROOT_KEY);
		Assert.assertEquals(0, json.get("source_count").asInt());
		Assert.assertEquals(4, json.get("total_available_sources").asInt());
		JsonNode keys = json.get("available_source_keys");
		Assert.assertEquals("Inner Add", keys.get(0).asText());
		Assert.assertEquals("Softmax_kernel", keys.get(1).asText());
		Assert.assertEquals(COPY_KEY, keys.get(2).asText());
		Assert.assertEquals(MATMUL_KEY, keys.get(3).asText());
		Assert.assertTrue(json.get("hint").asText().startsWith("No source found"));

		JsonNode missing = run("source", file, MISSING_KEY);
		Assert.assertEquals("Node not found: " + MISSING_KEY, missing.get("error").asText());
	}

	/**
	 * A profile without any operation sources reports no matches and omits the
	 * list of available keys.
	 */
	@Test(timeout = 60000)
	public void sourceOnProfileWithoutSources() throws IOException {
		OperationProfileNode root = node(ROOT_KEY, "Root");
		File file = Files.createTempFile("profile-analyzer-cli-empty", ".xml").toFile();
		file.deleteOnExit();
		root.save(file);

		JsonNode json = run("source", file.getAbsolutePath(), ROOT_KEY);
		Assert.assertEquals(0, json.get("source_count").asInt());
		Assert.assertFalse(json.has("available_source_keys"));
		Assert.assertTrue(json.has("hint"));
	}

	/**
	 * The source-summary command counts functions by type, detects duplicated
	 * function bodies (identical up to numeric identifier suffixes), and counts
	 * loops, conditionals and math calls in the kernel.
	 */
	@Test(timeout = 60000)
	public void sourceSummaryAnalyzesKernelStructure() throws IOException {
		JsonNode json = run("source-summary", saveSampleProfile(), MATMUL_KEY);

		Assert.assertEquals("MatMul", json.get("node_name").asText());
		Assert.assertEquals(16, json.get("total_lines").asInt());
		Assert.assertEquals(3, json.get("argument_count").asInt());
		Assert.assertEquals(3, json.get("total_functions").asInt());
		Assert.assertEquals(2, json.get("unique_function_bodies").asInt());
		Assert.assertEquals(1, json.get("duplicated_functions").asInt());

		JsonNode types = json.get("function_types");
		Assert.assertEquals(2, types.get("f_add").asInt());
		Assert.assertEquals(1, types.get("f_mul").asInt());

		JsonNode groups = json.get("duplicate_groups");
		Assert.assertEquals(1, groups.size());
		Assert.assertEquals("void f_add_*", groups.get(0).get("pattern").asText());
		Assert.assertEquals(2, groups.get(0).get("copies").asInt());
		Assert.assertEquals(5, groups.get(0).get("lines_per_copy").asInt());

		Assert.assertEquals(2, json.get("for_loops").asInt());
		Assert.assertEquals(1, json.get("if_statements").asInt());

		JsonNode math = json.get("math_operations");
		Assert.assertEquals(7, math.get("total").asInt());
		Assert.assertEquals(2, math.get("sin").asInt());
		Assert.assertEquals(2, math.get("cos").asInt());
		Assert.assertEquals(1, math.get("exp").asInt());
		Assert.assertEquals(1, math.get("pow").asInt());
		Assert.assertEquals(1, math.get("fabs").asInt());
		Assert.assertEquals(0, math.get("fmod").asInt());
		Assert.assertEquals(0, math.get("floor").asInt());
	}

	/**
	 * The source-summary command reports distinct errors for an unknown node, a
	 * node without an exactly matching source (it does not use partial matching),
	 * and a matched source whose text is null.
	 */
	@Test(timeout = 60000)
	public void sourceSummaryErrorCases() throws IOException {
		String file = saveSampleProfile();

		Assert.assertEquals("Node not found: " + MISSING_KEY,
				run("source-summary", file, MISSING_KEY).get("error").asText());
		Assert.assertEquals("No source found for node: " + SOFTMAX_KEY,
				run("source-summary", file, SOFTMAX_KEY).get("error").asText());
		Assert.assertEquals("Source is null for node: " + COPY_KEY,
				run("source-summary", file, COPY_KEY).get("error").asText());
	}

	/**
	 * The summary reports the number of compiled operations by consulting the operation
	 * sources stored on the root of the profile (keyed by node key), which for this
	 * fixture are the matrix-multiply and copy nodes.
	 */
	@Test(timeout = 60000)
	public void summaryCountsCompiledOperationsFromRootSources() throws IOException {
		JsonNode json = run("summary", saveSampleProfile());

		Assert.assertEquals("MatMul and Copy have sources registered under their keys",
				2, json.get("compiled_operations").asInt());
	}

	/**
	 * The slowest command reports {@code has_source} per node from the root's operation
	 * sources: the matrix-multiply node has a source registered under its key, whereas the
	 * softmax node's source is registered under a different key and so is not matched.
	 */
	@Test(timeout = 60000)
	public void slowestReportsHasSourceFromRootSources() throws IOException {
		JsonNode json = run("slowest", saveSampleProfile());

		JsonNode matmul = entry(json.get("slowest"), MATMUL_KEY);
		Assert.assertTrue("MatMul source is keyed by its node key", matmul.get("has_source").asBoolean());

		JsonNode softmax = entry(json.get("slowest"), SOFTMAX_KEY);
		Assert.assertFalse("Softmax source is keyed by name, not key", softmax.get("has_source").asBoolean());
	}

	/**
	 * Invoked with fewer than two arguments the CLI prints usage and reports a non-zero
	 * exit code without terminating the JVM.
	 */
	@Test(timeout = 60000)
	public void tooFewArgumentsReportNonZeroExit() {
		Assert.assertEquals(1, runForExit(new ArrayList<>()));
		Assert.assertEquals(1, runForExit(new ArrayList<>(), "summary"));
	}

	/**
	 * An unrecognized command reports a non-zero exit code.
	 */
	@Test(timeout = 60000)
	public void unknownCommandReportsNonZeroExit() throws IOException {
		Assert.assertEquals(1, runForExit(new ArrayList<>(), "bogus", saveSampleProfile()));
	}

	/**
	 * A command that requires a node-key or pattern argument reports a non-zero exit code
	 * when that argument is omitted.
	 */
	@Test(timeout = 60000)
	public void missingRequiredArgumentReportsNonZeroExit() throws IOException {
		String file = saveSampleProfile();
		Assert.assertEquals(1, runForExit(new ArrayList<>(), "search", file));
		Assert.assertEquals(1, runForExit(new ArrayList<>(), "breakdown", file));
		Assert.assertEquals(1, runForExit(new ArrayList<>(), "source", file));
		Assert.assertEquals(1, runForExit(new ArrayList<>(), "source-summary", file));
	}

	/**
	 * An unreadable profile path is caught and reported as an error JSON document with a
	 * non-zero exit code, rather than killing the process.
	 */
	@Test(timeout = 60000)
	public void unreadableProfileReportsErrorJsonAndNonZeroExit() throws IOException {
		List<String> output = new ArrayList<>();
		int exit = runForExit(output, "summary", "/no/such/profile-analyzer-missing.xml");

		Assert.assertEquals(1, exit);
		boolean sawError = false;
		for (String chunk : output) {
			if (JSON_LINE.matcher(chunk).find()) {
				JsonNode json = mapper.readTree(chunk.substring(chunk.indexOf('{')));
				if (json.has("error")) {
					Assert.assertTrue(json.has("type"));
					sawError = true;
				}
			}
		}
		Assert.assertTrue("An error JSON document should be printed", sawError);
	}

	/**
	 * A non-numeric line limit for the source command is caught (as a parse failure) and
	 * reported with a non-zero exit code rather than crashing the fork.
	 */
	@Test(timeout = 60000)
	public void nonNumericArgumentReportsNonZeroExit() throws IOException {
		Assert.assertEquals(1,
				runForExit(new ArrayList<>(), "source", saveSampleProfile(), MATMUL_KEY, "not-a-number"));
	}

	/**
	 * A command that emits a {@code "Node not found"} error document for an unresolved node key
	 * reports a non-zero exit code, so a caller that only inspects the exit status still sees the
	 * failure. This covers the {@code children}, {@code breakdown}, {@code source}, and
	 * {@code source-summary} commands, each of which resolves the key before producing output.
	 */
	@Test(timeout = 60000)
	public void missingNodeReportsErrorJsonAndNonZeroExit() throws IOException {
		String file = saveSampleProfile();

		for (String command : new String[] { "children", "breakdown", "source", "source-summary" }) {
			List<String> output = new ArrayList<>();
			int exit = runForExit(output, command, file, MISSING_KEY);

			Assert.assertEquals(command + " should exit non-zero for a missing node", 1, exit);

			boolean sawError = false;
			for (String chunk : output) {
				if (JSON_LINE.matcher(chunk).find()) {
					JsonNode json = mapper.readTree(chunk.substring(chunk.indexOf('{')));
					if (json.has("error")) {
						Assert.assertEquals("Node not found: " + MISSING_KEY, json.get("error").asText());
						sawError = true;
					}
				}
			}
			Assert.assertTrue(command + " should print an error JSON document", sawError);
		}
	}

	/**
	 * The {@code source-summary} command also reports a non-zero exit code when the node exists but
	 * has no (or a null) source, since both cases emit an error document.
	 */
	@Test(timeout = 60000)
	public void sourceSummaryWithoutSourceReportsNonZeroExit() throws IOException {
		String file = saveSampleProfile();

		Assert.assertEquals(1, runForExit(new ArrayList<>(), "source-summary", file, SOFTMAX_KEY));
		Assert.assertEquals(1, runForExit(new ArrayList<>(), "source-summary", file, COPY_KEY));
	}

	/**
	 * Commands that produce a valid document report a zero exit code, including the {@code source}
	 * command for a node that resolves but has no matching source — that is a well-formed
	 * no-source result carrying a hint, not an error.
	 */
	@Test(timeout = 60000)
	public void successfulCommandsReportZeroExit() throws IOException {
		String file = saveSampleProfile();

		Assert.assertEquals(0, runForExit(new ArrayList<>(), "summary", file));
		Assert.assertEquals(0, runForExit(new ArrayList<>(), "children", file));
		Assert.assertEquals(0, runForExit(new ArrayList<>(), "children", file, MATMUL_KEY));
		Assert.assertEquals(0, runForExit(new ArrayList<>(), "breakdown", file, MATMUL_KEY));
		Assert.assertEquals(0, runForExit(new ArrayList<>(), "source", file, MATMUL_KEY));
		Assert.assertEquals("A resolved node with no matching source is not an error",
				0, runForExit(new ArrayList<>(), "source", file, ROOT_KEY));
		Assert.assertEquals(0, runForExit(new ArrayList<>(), "source-summary", file, MATMUL_KEY));
	}

	/**
	 * A node key carrying a quote, a backslash, a tab and a newline is escaped in the
	 * error document, so the document parses and the key round-trips exactly.
	 */
	@Test(timeout = 60000)
	public void errorDocumentEscapesQuotesBackslashesAndWhitespace() throws IOException {
		String key = "k-\"quoted\"\\path\tcol\nline\r";
		JsonNode json = run("children", saveSampleProfile(), key);

		Assert.assertEquals("Node not found: " + key, json.get("error").asText());
	}

	/**
	 * A node key carrying control characters without a short escape (an ANSI colour
	 * sequence's {@code ESC}, a NUL) or with one other than tab/newline/return
	 * ({@code \b}, {@code \f}) is escaped in the error document. JSON forbids those
	 * characters literally, so a document that passed them through would be rejected
	 * by a strict parser such as the one the profile-analyzer MCP server uses.
	 */
	@Test(timeout = 60000)
	public void errorDocumentEscapesControlCharacters() throws IOException {
		String key = "k-\u001b[31mred\u001b[0m\b\f\u0000\u001f";
		JsonNode json = run("children", saveSampleProfile(), key);

		Assert.assertEquals("Node not found: " + key, json.get("error").asText());
	}
}
