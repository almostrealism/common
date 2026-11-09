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

import org.almostrealism.hardware.benchmark.InstanceResult.Outcome;
import org.almostrealism.hardware.benchmark.KernelBindingRecorder.Kernel;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The metrics of one {@link BenchmarkScenario} run on one compute context, computed from the
 * {@link InstanceResult}s of its instances.
 *
 * <p>Cache behaviour (hits, misses, collisions) is counted over every instance, because a
 * collision or a failed compilation is itself cache behaviour. The structure of the kernels
 * (kernels per computation, arguments, folded arguments, aggregate size, aggregate copies) is
 * measured over the {@link Outcome#VERIFIED verified} instances only, so a kernel that produced
 * the wrong output is never part of what is measured.</p>
 *
 * <p>Everything except the timing section is a deterministic function of the population and the
 * code under test: the same seed on the same code yields the same values, and a difference
 * between two branches run with the same seed is a difference in behaviour.</p>
 */
public class ScenarioMetrics {
	/** The longest detail kept for a collision or failure record. */
	private static final int DETAIL_LIMIT = 240;
	/** The most collision records kept per scenario and context. */
	private static final int COLLISION_RECORD_LIMIT = 40;

	/** The scenario. */
	private final BenchmarkScenario scenario;
	/** The name of the compute context it ran on. */
	private final String context;
	/** The results, in instance order. */
	private final List<InstanceResult> results;

	/** The number of results with each outcome. */
	private final Map<Outcome, Integer> outcomes = new TreeMap<>();
	/** The number of collisions of each kind. */
	private final Map<String, Integer> collisionKinds = new TreeMap<>();
	/** The number of mismatches and failures of each {@link InstanceResult#getProblem() problem}. */
	private final Map<String, Integer> problems = new TreeMap<>();
	/** The detail of the first instance with each problem, which is informational only. */
	private final Map<String, String> problemDetails = new TreeMap<>();
	/** Kernels compiled, whatever became of them, with more arguments than a Metal kernel can take. */
	private int compiledOverMetalLimit;

	/** Kernels compiled under a signature. */
	private int compiledShared;
	/** Kernels compiled without a signature, which can never be shared. */
	private int compiledUnshared;
	/** Kernels compiled under a signature this scenario had already compiled. */
	private int recompiled;
	/** Kernels reused from the cache. */
	private int reused;
	/** Distinct signatures compiled. */
	private final Set<String> signatures = new HashSet<>();

	/** Kernels obtained per verified computation, as a histogram. */
	private final Histogram kernelsPerComputation = new Histogram();
	/** Arguments per kernel of verified computations, as a histogram. */
	private final Histogram argumentsPerKernel = new Histogram();
	/** Folded arguments per kernel compiled for verified computations, as a histogram. */
	private final Histogram foldedPerKernel = new Histogram();
	/** The total and maximum aggregate length of compiled kernels with an aggregate. */
	private long aggregateLengthTotal;
	/** The longest aggregate. */
	private int aggregateLengthMax;
	/** The number of compiled kernels with an aggregate. */
	private int aggregatingKernels;

	/** Dispatches of the first evaluation of verified computations. */
	private long firstDispatches;
	/** Aggregating dispatches of first evaluations. */
	private long firstAggregatingDispatches;
	/** Slices copied in by first evaluations. */
	private long firstCopiesIn;
	/** Slices copied back by first evaluations. */
	private long firstCopiesBack;
	/** Dispatches of later evaluations. */
	private long repeatDispatches;
	/** Aggregating dispatches of later evaluations. */
	private long repeatAggregatingDispatches;
	/** Slices copied in by later evaluations. */
	private long repeatCopiesIn;
	/** Slices copied back by later evaluations. */
	private long repeatCopiesBack;

	/** Native compilations. */
	private long nativeCompilations;
	/** Native compilation time. */
	private long nativeCompileNanos;
	/** Wall-clock time to the end of each first evaluation. */
	private long firstEvaluationNanos;

	/**
	 * Computes the metrics of a scenario run.
	 *
	 * @param scenario the scenario
	 * @param context  the name of the compute context it ran on
	 * @param results  the results, in instance order
	 */
	public ScenarioMetrics(BenchmarkScenario scenario, String context, List<InstanceResult> results) {
		this.scenario = scenario;
		this.context = context;
		this.results = results;

		for (InstanceResult result : results) {
			outcomes.merge(result.getOutcome(), 1, Integer::sum);
			if (result.getOutcome() == Outcome.COLLISION) {
				collisionKinds.merge(result.getCollisionKind().name(), 1, Integer::sum);
			} else if (result.getOutcome() == Outcome.FAILURE || result.getOutcome() == Outcome.MISMATCH) {
				problems.merge(result.getProblem(), 1, Integer::sum);
				problemDetails.putIfAbsent(result.getProblem(), category(result.getDetail()));
			}

			countCache(result.getFirst().getKernels());
			countCache(result.getRepeat().getKernels());
			nativeCompilations += result.getFirst().getNativeCompilations() + result.getRepeat().getNativeCompilations();
			nativeCompileNanos += result.getFirst().getNativeCompileNanos() + result.getRepeat().getNativeCompileNanos();
			firstEvaluationNanos += result.getFirstEvaluationNanos();

			if (result.getOutcome() == Outcome.VERIFIED) countStructure(result);
		}
	}

	/** Counts the cache hits and misses of a list of obtained kernels. */
	private void countCache(List<Kernel> kernels) {
		for (Kernel kernel : kernels) {
			if (!kernel.isCompiled()) {
				reused++;
			} else if (kernel.getSignature() == null) {
				if (kernel.getArguments() > WorkloadGenerator.METAL_ARGUMENT_LIMIT) compiledOverMetalLimit++;
				compiledUnshared++;
			} else {
				compiledShared++;
				if (kernel.getArguments() > WorkloadGenerator.METAL_ARGUMENT_LIMIT) compiledOverMetalLimit++;
				if (!signatures.add(kernel.getSignature())) recompiled++;
			}
		}
	}

	/** Counts the kernel structure and copies of a verified result. */
	private void countStructure(InstanceResult result) {
		List<Kernel> kernels = result.getFirst().getKernels();
		kernelsPerComputation.add(kernels.size());

		for (Kernel kernel : kernels) {
			argumentsPerKernel.add(kernel.getArguments());
			if (!kernel.isCompiled()) continue;

			foldedPerKernel.add(kernel.getFolded());
			if (kernel.getAggregateLength() > 0) {
				aggregatingKernels++;
				aggregateLengthTotal += kernel.getAggregateLength();
				aggregateLengthMax = Math.max(aggregateLengthMax, kernel.getAggregateLength());
			}
		}

		KernelBindingRecorder.Events first = result.getFirst();
		firstDispatches += first.getDispatches();
		firstAggregatingDispatches += first.getAggregatingDispatches();
		firstCopiesIn += first.getCopiesIn();
		firstCopiesBack += first.getCopiesBack();

		KernelBindingRecorder.Events repeat = result.getRepeat();
		repeatDispatches += repeat.getDispatches();
		repeatAggregatingDispatches += repeat.getAggregatingDispatches();
		repeatCopiesIn += repeat.getCopiesIn();
		repeatCopiesBack += repeat.getCopiesBack();
	}

	/**
	 * Reduces a failure description to a category that does not depend on run order: the first
	 * line, with the numeric suffixes of generated function names removed, truncated.
	 */
	private static String category(String detail) {
		if (detail == null) return "";
		String line = detail.lines().findFirst().orElse("");
		line = line.replaceAll("_\\d+", "_N");
		return line.length() > DETAIL_LIMIT ? line.substring(0, DETAIL_LIMIT) : line;
	}

	/** Returns the number of kernels the cache was asked for: compiled plus reused. */
	public int getKernelRequests() { return compiledShared + compiledUnshared + reused; }

	/** Returns the fraction of kernel requests served from the cache. */
	public double getHitRate() { return ratio(reused, getKernelRequests()); }

	/** Returns the fraction of cache hits that were collisions. */
	public double getCollisionRate() { return ratio(count(Outcome.COLLISION), reused); }

	/** Returns the number of results with the given outcome. */
	private int count(Outcome outcome) { return outcomes.getOrDefault(outcome, 0); }

	/** Returns {@code a / b}, or 0 when {@code b} is 0. */
	private static double ratio(double a, double b) { return b == 0 ? 0.0 : a / b; }

	/** Formats a ratio with a fixed number of decimals, so the report text is deterministic. */
	private static String fixed(double value) { return String.format(Locale.ROOT, "%.4f", value); }

	/**
	 * Escapes a string as a JSON string literal.
	 *
	 * @param value the string
	 * @return the quoted, escaped literal
	 */
	static String quote(String value) {
		StringBuilder b = new StringBuilder("\"");
		for (char c : value.toCharArray()) {
			if (c == '"' || c == '\\') {
				b.append('\\').append(c);
			} else if (c < 0x20) {
				b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
			} else {
				b.append(c);
			}
		}

		return b.append('"').toString();
	}

	/** Renders a string-keyed count map as a JSON object. */
	private static String counts(Map<String, Integer> counts) {
		StringBuilder b = new StringBuilder("{");
		counts.forEach((k, v) -> b.append(b.length() > 1 ? ", " : "").append(quote(k)).append(": ").append(v));
		return b.append('}').toString();
	}

	/**
	 * Renders these metrics as a JSON object, its lines indented by {@code indent}.
	 *
	 * @param indent the indentation of the object's lines
	 * @return the JSON object
	 */
	public String toJson(String indent) {
		String in = indent + "  ";
		StringBuilder b = new StringBuilder("{\n");
		b.append(in).append("\"scenario\": ").append(quote(scenario.getName())).append(",\n");
		b.append(in).append("\"context\": ").append(quote(context)).append(",\n");
		b.append(in).append("\"instances\": ").append(results.size()).append(",\n");
		b.append(in).append("\"outcomes\": {");
		boolean firstOutcome = true;
		for (Outcome outcome : Outcome.values()) {
			b.append(firstOutcome ? "" : ", ").append('"').append(outcome).append("\": ").append(count(outcome));
			firstOutcome = false;
		}
		b.append("},\n");
		b.append(in).append("\"cache\": {\"requests\": ").append(getKernelRequests())
				.append(", \"hits\": ").append(reused)
				.append(", \"compiledShared\": ").append(compiledShared)
				.append(", \"compiledUnshared\": ").append(compiledUnshared)
				.append(", \"recompiledSignatures\": ").append(recompiled)
				.append(", \"distinctSignatures\": ").append(signatures.size())
				.append(", \"compiledOverMetalLimit\": ").append(compiledOverMetalLimit)
				.append(", \"hitRate\": ").append(fixed(getHitRate()))
				.append(", \"collisions\": ").append(count(Outcome.COLLISION))
				.append(", \"collisionRate\": ").append(fixed(getCollisionRate()))
				.append(", \"collisionKinds\": ").append(counts(collisionKinds)).append("},\n");
		b.append(in).append("\"kernelsPerComputation\": {\"mean\": ").append(fixed(kernelsPerComputation.mean()))
				.append(", \"max\": ").append(kernelsPerComputation.max())
				.append(", \"histogram\": ").append(kernelsPerComputation.toJson()).append("},\n");
		b.append(in).append("\"argumentsPerKernel\": {\"mean\": ").append(fixed(argumentsPerKernel.mean()))
				.append(", \"max\": ").append(argumentsPerKernel.max())
				.append(", \"overMetalLimit\": ").append(argumentsPerKernel.atLeast(WorkloadGenerator.METAL_ARGUMENT_LIMIT + 1))
				.append(", \"histogram\": ").append(argumentsPerKernel.toJson()).append("},\n");
		b.append(in).append("\"foldedPerCompiledKernel\": {\"mean\": ").append(fixed(foldedPerKernel.mean()))
				.append(", \"max\": ").append(foldedPerKernel.max())
				.append(", \"histogram\": ").append(foldedPerKernel.toJson()).append("},\n");
		b.append(in).append("\"aggregate\": {\"kernels\": ").append(aggregatingKernels)
				.append(", \"meanLength\": ").append(fixed(ratio(aggregateLengthTotal, aggregatingKernels)))
				.append(", \"maxLength\": ").append(aggregateLengthMax).append("},\n");
		b.append(in).append("\"copies\": {")
				.append("\"firstDispatches\": ").append(firstDispatches)
				.append(", \"firstAggregatingDispatches\": ").append(firstAggregatingDispatches)
				.append(", \"firstCopiesIn\": ").append(firstCopiesIn)
				.append(", \"firstCopiesBack\": ").append(firstCopiesBack)
				.append(", \"repeatDispatches\": ").append(repeatDispatches)
				.append(", \"repeatAggregatingDispatches\": ").append(repeatAggregatingDispatches)
				.append(", \"repeatCopiesIn\": ").append(repeatCopiesIn)
				.append(", \"repeatCopiesBack\": ").append(repeatCopiesBack)
				.append(", \"copiesInPerAggregatingDispatch\": ")
				.append(fixed(ratio(firstCopiesIn + repeatCopiesIn, firstAggregatingDispatches + repeatAggregatingDispatches)))
				.append(", \"copiesBackPerAggregatingDispatch\": ")
				.append(fixed(ratio(firstCopiesBack + repeatCopiesBack, firstAggregatingDispatches + repeatAggregatingDispatches)))
				.append("},\n");
		b.append(in).append("\"problems\": ").append(counts(problems)).append(",\n");
		b.append(in).append("\"problemDetails\": {");
		problemDetails.forEach((k, v) -> b.append(b.charAt(b.length() - 1) == '{' ? "" : ", ")
				.append(quote(k)).append(": ").append(quote(v)));
		b.append("},\n");
		b.append(in).append("\"collisionRecords\": [");
		int records = 0;
		for (InstanceResult result : results) {
			if (result.getOutcome() != Outcome.COLLISION || records >= COLLISION_RECORD_LIMIT) continue;
			b.append(records++ == 0 ? "\n" : ",\n").append(in).append("  {\"instance\": ")
					.append(quote(result.getInstance().describe()))
					.append(", \"kind\": ").append(quote(result.getCollisionKind().name()))
					.append(", \"signatures\": ").append(quote(category(collidedSignatures(result))))
					.append(", \"detail\": ").append(quote(category(result.getDetail()))).append('}');
		}
		b.append(records > 0 ? "\n" + in : "").append("],\n");
		b.append(in).append("\"timing\": {\"nativeCompilations\": ").append(nativeCompilations)
				.append(", \"nativeCompileMillis\": ").append(fixed(nativeCompileNanos / 1e6))
				.append(", \"meanNativeCompileMillis\": ").append(fixed(ratio(nativeCompileNanos / 1e6, nativeCompilations)))
				.append(", \"firstEvaluationMillis\": ").append(fixed(firstEvaluationNanos / 1e6)).append("},\n");
		b.append(in).append("\"instanceRecords\": [");
		for (int i = 0; i < results.size(); i++) {
			b.append(i == 0 ? "\n" : ",\n").append(in).append("  ").append(record(results.get(i)));
		}
		b.append(results.isEmpty() ? "" : "\n" + in).append("]\n");
		return b.append(indent).append('}').toString();
	}

	/** Returns the signatures of the kernels the collided instance reused, which are the ones that collided. */
	private static String collidedSignatures(InstanceResult result) {
		StringBuilder b = new StringBuilder();
		for (Kernel kernel : result.getFirst().getKernels()) {
			if (kernel.isCompiled()) continue;
			if (b.length() > 0) b.append(" ; ");
			b.append(kernel.getSignature());
		}

		return b.toString();
	}

	/** Renders one instance as a single-line JSON object. */
	private static String record(InstanceResult result) {
		StringBuilder kernels = new StringBuilder("[");
		for (Kernel kernel : result.getFirst().getKernels()) {
			if (kernels.length() > 1) kernels.append(", ");
			kernels.append(quote((kernel.isCompiled() ? (kernel.getSignature() == null ? "u" : "c") : "h")
					+ kernel.getArguments() + (kernel.isCompiled() ? "/" + kernel.getFolded() : "")));
		}
		kernels.append(']');

		WorkloadInstance instance = result.getInstance();
		return "{\"template\": " + quote(instance.getTemplate().getId()) +
				", \"shape\": " + quote(instance.describeShape()) +
				", \"aliasing\": " + quote(instance.getAliasing().name()) +
				", \"roots\": " + quote(Arrays.toString(instance.getRootLengths())) +
				", \"outcome\": " + quote(result.getOutcome().name()) +
				", \"kernels\": " + kernels +
				", \"copies\": [" + result.getFirst().getCopiesIn() + ", " + result.getFirst().getCopiesBack() +
				", " + result.getRepeat().getCopiesIn() + ", " + result.getRepeat().getCopiesBack() + "]}";
	}

	/**
	 * Renders these metrics as one row of the summary table.
	 *
	 * @return the markdown table row
	 */
	public String toMarkdownRow() {
		return "| " + scenario.getName() + " | " + context + " | " + results.size() +
				" | " + count(Outcome.VERIFIED) + "/" + count(Outcome.MISMATCH) + "/" +
				count(Outcome.COLLISION) + "/" + count(Outcome.FAILURE) +
				" | " + fixed(getHitRate()) + " | " + fixed(scenario.structuralRepeatRate()) +
				" | " + fixed(getCollisionRate()) +
				" | " + fixed(kernelsPerComputation.mean()) +
				" | " + fixed(argumentsPerKernel.mean()) + " (" + argumentsPerKernel.max() + ")" +
				" | " + fixed(foldedPerKernel.mean()) + " (" + foldedPerKernel.max() + ")" +
				" | " + fixed(ratio(aggregateLengthTotal, aggregatingKernels)) +
				" | " + fixed(ratio(firstCopiesIn + repeatCopiesIn, firstAggregatingDispatches + repeatAggregatingDispatches)) +
				" / " + fixed(ratio(firstCopiesBack + repeatCopiesBack, firstAggregatingDispatches + repeatAggregatingDispatches)) +
				" | " + fixed(ratio(nativeCompileNanos / 1e6, nativeCompilations)) + " |";
	}

	/**
	 * Returns the problem categories of this run, for the summary.
	 *
	 * @return the number of mismatches and failures in each category
	 */
	public Map<String, Integer> getProblems() { return problems; }

	/**
	 * Returns the detail of the first instance with each problem.
	 *
	 * @return the details, keyed as {@link #getProblems()}
	 */
	public Map<String, String> getProblemDetails() { return problemDetails; }

	/** Returns the scenario. */
	public BenchmarkScenario getScenario() { return scenario; }

	/** Returns the name of the compute context. */
	public String getContext() { return context; }
}
