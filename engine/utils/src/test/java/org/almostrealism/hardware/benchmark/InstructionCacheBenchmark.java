/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.hardware.benchmark;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.DefaultComputer;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.InstructionCacheCollisionException;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.benchmark.InstanceResult.Outcome;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Measures how the instruction cache behaves on a seeded, generated population of computations.
 *
 * <p>Every instance of every {@link BenchmarkScenario} is built as a fresh computation over fresh
 * inputs, passed through {@link Process#optimize()} (which decides how the work is split into
 * kernels), compiled and evaluated several times, exactly as an application would use it. The
 * kernel binding hooks ({@link org.almostrealism.hardware.KernelBindingListener} and the
 * compilation timing listener) record, per instance, every kernel compiled or reused, its
 * argument count and folded inputs, the dispatches and the aggregate copies around them. Every
 * output is checked against a host reference, and an
 * {@link InstructionCacheCollisionException} is counted as a collision while any other failure
 * is counted as a failure; neither stops the run.</p>
 *
 * <p>Each scenario is run on each requested compute context against a cache holding nothing the
 * scenario did not compile: the instruction cache is emptied before and after it, so scenarios,
 * contexts and any earlier work in the JVM do not share kernels and their order does not
 * matter.</p>
 *
 * <p>The benchmark is a measurement, not a check, and it runs for many minutes, so it is kept out
 * of the CI pipeline and out of ordinary local runs. Run it with long tests enabled:</p>
 * <pre>
 * AR_LONG_TESTS=enabled AR_CACHE_BENCHMARK_SEED=1729 AR_CACHE_BENCHMARK_POPULATION=200 \
 *     mvn -pl engine/utils test -Dtest=InstructionCacheBenchmark
 * </pre>
 * <p>It writes {@code <label>.json} and {@code <label>.md} to
 * {@code engine/utils/results/instruction-cache-benchmark}. The parameters, their defaults and
 * the comparison script are described in {@code docs/internals/instruction-cache-benchmark.md}.</p>
 */
public class InstructionCacheBenchmark extends TestSuiteBase {
	/** The property prefix of every benchmark parameter. */
	private static final String PREFIX = "AR_CACHE_BENCHMARK_";

	/** Things the current cache design does not let the benchmark measure, stated in every report. */
	private static final List<String> LIMITATIONS = List.of(
			"A hit is an operation binding a kernel another operation compiled under the same signature " +
					"and compute context. Reuse of a compiled operation by its own owner (evaluating one " +
					"Evaluable repeatedly) is not a cache request and is not counted.",
			"Folded arguments and aggregate length are known only for kernels an instance compiled; a " +
					"reused kernel reports its argument count, and its aggregate is verified to have the " +
					"compiled positions but is not measured again.",
			"A kernel the backend fails to compile (for example one over the Metal argument limit) has " +
					"already been counted as a compiled kernel when its native compilation fails; the " +
					"failure is reported among the problems.",
			"Native compilation time is the only compile time the hooks report; building and optimizing " +
					"the computation and generating its scope are included only in firstEvaluationMillis.",
			"Every value in the timing sections depends on the machine and its load. Every other value " +
					"is a deterministic function of the seed and the code under test.");

	/**
	 * Runs every scenario on every requested compute context and writes the report.
	 *
	 * @throws IOException if the report cannot be written
	 */
	@Test(timeout = 2400000)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void instructionCacheBenchmark() throws IOException {
		long seed = SystemUtils.getInt(PREFIX + "SEED").orElse(1729);
		int population = SystemUtils.getInt(PREFIX + "POPULATION").orElse(200);
		int evaluations = Math.max(1, SystemUtils.getInt(PREFIX + "EVALUATIONS").orElse(3));
		String label = SystemUtils.getProperty(PREFIX + "LABEL", "report");
		Path output = Paths.get(SystemUtils.getProperty(PREFIX + "OUTPUT", "results/instruction-cache-benchmark"));
		List<String> contexts = List.of(SystemUtils.getProperty(PREFIX + "CONTEXTS", "JNI,MTL").split(","));
		Set<String> selected = new LinkedHashSet<>(List.of(
				SystemUtils.getProperty(PREFIX + "SCENARIOS",
						"generated,size-generic,aliasing,aggregate-threshold,argument-limit").split(",")));

		WorkloadGenerator generator = new WorkloadGenerator(seed,
				SystemUtils.getInt(PREFIX + "TEMPLATES").orElse(WorkloadGenerator.DEFAULT_TEMPLATES),
				doubleProperty("TEMPLATE_SKEW", WorkloadGenerator.DEFAULT_TEMPLATE_SKEW),
				doubleProperty("SHAPE_SKEW", WorkloadGenerator.DEFAULT_SHAPE_SKEW),
				doubleProperty("CONSTANT_SKEW", WorkloadGenerator.DEFAULT_CONSTANT_SKEW),
				doubleProperty("ALIAS_PROBABILITY", WorkloadGenerator.DEFAULT_ALIAS_PROBABILITY),
				SystemUtils.getInt(PREFIX + "MAX_DEPTH").orElse(WorkloadGenerator.DEFAULT_MAX_DEPTH),
				SystemUtils.getInt(PREFIX + "MAX_INPUTS").orElse(WorkloadGenerator.DEFAULT_MAX_INPUTS),
				doubleProperty("SCALAR_PROBABILITY", WorkloadGenerator.DEFAULT_SCALAR_PROBABILITY),
				doubleProperty("LITERAL_PROBABILITY", WorkloadGenerator.DEFAULT_LITERAL_PROBABILITY),
				MemoryDataArgumentMap.maxAggregateLength);

		List<BenchmarkScenario> scenarios = new ArrayList<>();
		for (BenchmarkScenario scenario : generator.scenarios(population)) {
			if (selected.contains(scenario.getName())) scenarios.add(scenario);
		}

		Assert.assertFalse("No scenario selected from " + selected, scenarios.isEmpty());

		List<String[]> contextRecords = new ArrayList<>();
		List<ScenarioMetrics> results = new ArrayList<>();
		KernelBindingRecorder recorder = new KernelBindingRecorder();
		recorder.install();

		try {
			for (String name : contexts) {
				ComputeRequirement requirement = ComputeRequirement.valueOf(name.trim());
				String available = availableContext(requirement);
				contextRecords.add(new String[] { requirement.name(), available });
				if (available == null) {
					warn("contextUnavailable=" + requirement);
					continue;
				}

				for (BenchmarkScenario scenario : scenarios) {
					results.add(run(scenario, requirement, recorder, evaluations));
				}
			}
		} finally {
			recorder.uninstall();
		}

		Assert.assertFalse("No requested compute context is available: " + contexts, results.isEmpty());

		String command = command(seed, population);
		Files.createDirectories(output);
		Files.writeString(output.resolve(label + ".json"),
				json(command, generator, population, evaluations, scenarios, contextRecords, results),
				StandardCharsets.UTF_8);
		Files.writeString(output.resolve(label + ".md"),
				markdown(command, generator, population, evaluations, scenarios, contextRecords, results),
				StandardCharsets.UTF_8);
		log("reportWritten=" + output.resolve(label + ".json").toAbsolutePath());
	}

	/** Reads a double-valued benchmark parameter. */
	private static double doubleProperty(String name, double defaultValue) {
		return Double.parseDouble(SystemUtils.getProperty(PREFIX + name, Double.toString(defaultValue)));
	}

	/**
	 * Returns the class name of the compute context that serves the given requirement, or null
	 * when no configured context does.
	 */
	private static String availableContext(ComputeRequirement requirement) {
		try {
			List<ComputeContext<MemoryData>> contexts =
					Hardware.getLocalHardware().getComputeContexts(false, false, requirement);
			return contexts.isEmpty() ? null : contexts.get(0).getClass().getSimpleName();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Runs every instance of a scenario under a compute requirement against an empty instruction
	 * cache, and empties it again afterward, so the scenario neither reuses kernels compiled by
	 * earlier work in the JVM nor leaves any for the next run.
	 */
	private ScenarioMetrics run(BenchmarkScenario scenario, ComputeRequirement requirement,
								KernelBindingRecorder recorder, int evaluations) {
		DefaultComputer computer = Hardware.getLocalHardware().getComputer();
		List<InstanceResult> results = new ArrayList<>();

		computer.evictAllInstructions();
		computer.pushRequirements(List.of(requirement));

		try {
			for (WorkloadInstance instance : scenario.getInstances()) {
				results.add(run(instance, recorder, evaluations));
			}
		} finally {
			computer.popRequirements();
			computer.evictAllInstructions();
		}

		ScenarioMetrics metrics = new ScenarioMetrics(scenario, requirement.name(), results);
		log("scenario=" + scenario.getName() + " context=" + requirement + " " + metrics.toMarkdownRow());
		return metrics;
	}

	/**
	 * Builds, optimizes, compiles and evaluates one instance, checking every output, and returns
	 * what happened together with the events it caused. No runtime exception it throws escapes,
	 * including one raised while allocating its inputs. The first-evaluation time ends when the
	 * first output has been read back, so it does not include verifying that output.
	 */
	private InstanceResult run(WorkloadInstance instance, KernelBindingRecorder recorder, int evaluations) {
		KernelBindingRecorder.Events first = null;
		Outcome outcome = Outcome.VERIFIED;
		InstructionCacheCollisionException.Kind kind = null;
		String detail = null;
		String failureType = null;
		boolean allocated = false;
		long start = System.nanoTime();
		long firstNanos = 0;

		try {
			instance.allocate(this);
			allocated = true;
			recorder.drain();
			start = System.nanoTime();

			CollectionProducer producer = instance.build(this);
			Evaluable<? extends PackedCollection> evaluable = Process.optimized(producer).get();

			for (int i = 0; i < evaluations; i++) {
				PackedCollection out = evaluable.evaluate();
				double[] values = out.toArray();

				if (i == 0) {
					firstNanos = System.nanoTime() - start;
					first = recorder.drain();
				}

				out.destroy();
				String mismatch = instance.verify(values);
				if (mismatch != null && detail == null) {
					outcome = Outcome.MISMATCH;
					detail = "evaluation " + i + ": " + mismatch;
				}
			}
		} catch (RuntimeException e) {
			InstructionCacheCollisionException collision = InstructionCacheCollisionException.findIn(e);
			if (collision != null) {
				outcome = Outcome.COLLISION;
				kind = collision.getKind();
				detail = collision.getMessage();
			} else {
				outcome = Outcome.FAILURE;
				detail = describe(e);
				failureType = rootCause(e).getClass().getSimpleName();
			}

			warn("instance=" + instance.describe() + " outcome=" + outcome + " detail=" + detail);
		} finally {
			instance.release();
		}

		if (first == null) {
			firstNanos = System.nanoTime() - start;

			// The events of a failed allocation belong to its random inputs, not to the instance
			if (!allocated) recorder.drain();
			first = recorder.drain();
		}

		return new InstanceResult(instance, outcome, kind, detail, failureType, first, recorder.drain(), firstNanos);
	}

	/** Returns the innermost cause of a failure's chain. */
	private static Throwable rootCause(Throwable failure) {
		Throwable root = failure;
		while (root.getCause() != null && root.getCause() != root) root = root.getCause();
		return root;
	}

	/**
	 * Describes a failure by the innermost cause of its chain, which names what actually failed,
	 * and the method that threw it. The line number is left out, so the description of the same
	 * failure does not change when unrelated lines of that method's file do.
	 */
	private static String describe(Throwable failure) {
		Throwable root = rootCause(failure);

		String origin = "";
		if (root.getStackTrace().length > 0) {
			StackTraceElement frame = root.getStackTrace()[0];
			String type = frame.getClassName();
			origin = " (in " + type.substring(type.lastIndexOf('.') + 1) + "." + frame.getMethodName() + ")";
		}

		return root.getClass().getSimpleName() + origin + ": " + root.getMessage();
	}

	/** Returns the command that reproduces this report. */
	private static String command(long seed, int population) {
		StringBuilder b = new StringBuilder("AR_LONG_TESTS=enabled");
		b.append(" ").append(PREFIX).append("SEED=").append(seed);
		b.append(" ").append(PREFIX).append("POPULATION=").append(population);
		for (String name : List.of("EVALUATIONS", "LABEL", "OUTPUT", "CONTEXTS", "SCENARIOS", "TEMPLATES", "TEMPLATE_SKEW",
				"SHAPE_SKEW", "CONSTANT_SKEW", "ALIAS_PROBABILITY", "MAX_DEPTH", "MAX_INPUTS",
				"SCALAR_PROBABILITY", "LITERAL_PROBABILITY")) {
			String value = SystemUtils.getProperty(PREFIX + name);
			if (value != null) b.append(" ").append(PREFIX).append(name).append('=').append(value);
		}

		return b.append(" mvn -pl engine/utils test -Dtest=InstructionCacheBenchmark").toString();
	}

	/** Renders the machine-readable report. */
	private static String json(String command, WorkloadGenerator generator, int population, int evaluations,
							   List<BenchmarkScenario> scenarios, List<String[]> contexts,
							   List<ScenarioMetrics> results) {
		StringBuilder b = new StringBuilder("{\n");
		b.append("  \"benchmark\": \"instruction-cache\",\n");
		b.append("  \"formatVersion\": 1,\n");
		b.append("  \"command\": ").append(ScenarioMetrics.quote(command)).append(",\n");
		b.append("  \"parameters\": {");
		for (String[] parameter : parameters(generator, population, evaluations)) {
			b.append(b.charAt(b.length() - 1) == '{' ? "" : ", ").append(ScenarioMetrics.quote(parameter[0])).append(": ")
					.append(ScenarioMetrics.quote(parameter[1]));
		}
		b.append("},\n");

		b.append("  \"scenarios\": [");
		for (int i = 0; i < scenarios.size(); i++) {
			BenchmarkScenario s = scenarios.get(i);
			b.append(i == 0 ? "\n" : ",\n").append("    {\"name\": ").append(ScenarioMetrics.quote(s.getName()))
					.append(", \"purpose\": ").append(ScenarioMetrics.quote(s.getPurpose()))
					.append(", \"instances\": ").append(s.getInstances().size())
					.append(", \"distinctTemplates\": ").append(s.distinctTemplates())
					.append(", \"structuralRepeatRate\": ").append(String.format(Locale.ROOT, "%.4f", s.structuralRepeatRate()))
					.append(", \"fingerprint\": ").append(ScenarioMetrics.quote(s.fingerprint())).append('}');
		}
		b.append("\n  ],\n");

		b.append("  \"contexts\": [");
		for (int i = 0; i < contexts.size(); i++) {
			b.append(i == 0 ? "" : ", ").append("{\"requirement\": ").append(ScenarioMetrics.quote(contexts.get(i)[0]))
					.append(", \"context\": ").append(contexts.get(i)[1] == null ? "null" : ScenarioMetrics.quote(contexts.get(i)[1]))
					.append('}');
		}
		b.append("],\n");

		b.append("  \"limitations\": [");
		for (int i = 0; i < LIMITATIONS.size(); i++) {
			b.append(i == 0 ? "\n" : ",\n").append("    ").append(ScenarioMetrics.quote(LIMITATIONS.get(i)));
		}
		b.append("\n  ],\n");

		b.append("  \"results\": [");
		for (int i = 0; i < results.size(); i++) {
			b.append(i == 0 ? "\n    " : ",\n    ").append(results.get(i).toJson("    "));
		}
		b.append("\n  ]\n}\n");
		return b.toString();
	}

	/** Renders the summary. */
	private static String markdown(String command, WorkloadGenerator generator, int population, int evaluations,
								   List<BenchmarkScenario> scenarios, List<String[]> contexts,
								   List<ScenarioMetrics> results) {
		StringBuilder b = new StringBuilder("# Instruction-cache benchmark\n\n");
		b.append("Produced by:\n\n```\n").append(command).append("\n```\n\n");

		b.append("## Parameters\n\n| Parameter | Value |\n|---|---|\n");
		for (String[] parameter : parameters(generator, population, evaluations)) {
			b.append("| ").append(parameter[0]).append(" | ").append(parameter[1]).append(" |\n");
		}

		b.append("\n## Scenarios\n\n| Scenario | Instances | Templates | Structural repeat | Purpose |\n|---|---|---|---|---|\n");
		for (BenchmarkScenario s : scenarios) {
			b.append("| ").append(s.getName()).append(" | ").append(s.getInstances().size())
					.append(" | ").append(s.distinctTemplates())
					.append(" | ").append(String.format(Locale.ROOT, "%.4f", s.structuralRepeatRate()))
					.append(" | ").append(s.getPurpose()).append(" |\n");
		}

		b.append("\n## Contexts\n\n");
		for (String[] context : contexts) {
			b.append("- ").append(context[0]).append(": ")
					.append(context[1] == null ? "not available on this host" : context[1]).append('\n');
		}

		b.append("\n## Results\n\nOutcomes are verified/mismatch/collision/failure. Hit rate is reused kernels over ")
				.append("kernel requests; collision rate is collisions over hits. Arguments and folded inputs are ")
				.append("means with the maximum in parentheses; copies are per aggregating dispatch (in / back); ")
				.append("compile is the mean native compilation in milliseconds and is the only machine-dependent ")
				.append("column.\n\n")
				.append("| Scenario | Context | Instances | Outcomes | Hit rate | Structural repeat | Collision rate ")
				.append("| Kernels/computation | Arguments/kernel | Folded/kernel | Aggregate length | Copies | Compile ms |\n")
				.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
		results.forEach(r -> b.append(r.toMarkdownRow()).append('\n'));

		b.append("\n## Problems\n\n");
		boolean none = true;
		for (ScenarioMetrics r : results) {
			for (Map.Entry<String, Integer> problem : r.getProblems().entrySet()) {
				none = false;
				b.append("- ").append(r.getScenario().getName()).append(" / ").append(r.getContext())
						.append(": ").append(problem.getValue()).append(" x ").append(problem.getKey())
						.append(", first seen as `").append(r.getProblemDetails().get(problem.getKey())).append("`\n");
			}
		}

		if (none) b.append("None: every instance was verified or collided.\n");

		b.append("\n## Limitations\n\n");
		LIMITATIONS.forEach(l -> b.append("- ").append(l).append('\n'));
		return b.toString();
	}

	/** Returns every parameter that determines the population, in a fixed order. */
	private static List<String[]> parameters(WorkloadGenerator generator, int population, int evaluations) {
		List<String[]> parameters = new ArrayList<>();
		parameters.add(new String[] { "population", Integer.toString(population) });
		parameters.add(new String[] { "evaluations", Integer.toString(evaluations) });
		parameters.addAll(generator.describeParameters());
		parameters.add(new String[] { "argumentAggregation", Boolean.toString(MemoryDataArgumentMap.enableArgumentAggregation) });
		return parameters;
	}
}
