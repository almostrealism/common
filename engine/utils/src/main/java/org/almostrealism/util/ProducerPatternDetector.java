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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects Producer pattern violations in computation source trees.
 *
 * <p>In the Almost Realism framework, Java is the orchestration language — it builds
 * computation graphs that the framework compiles to native GPU/CPU kernels. Calling
 * {@code .evaluate()} or {@code .toDouble()} inside model layers breaks the computation
 * graph, disabling hardware acceleration and automatic differentiation.
 *
 * <p>This detector flags:
 * <ul>
 *   <li>{@code PRODUCER_EVALUATE_IN_COMPUTATION} — {@code .evaluate()} calls inside
 *       computation source trees ({@code engine/ml/}, {@code studio/})</li>
 *   <li>{@code PRODUCER_TODOUBLE_IN_COMPUTATION} — {@code .toDouble()} calls inside
 *       computation source trees</li>
 * </ul>
 *
 * <p>Files listed in {@link #EVALUATE_ALLOWED_FILES} and {@link #TODOUBLE_ALLOWED_FILES}
 * are pipeline boundaries where these calls are legitimate (data loading, sampling loop
 * boundaries, monitoring code).
 *
 * @see PolicyViolationDetector
 */
public class ProducerPatternDetector extends PolicyViolationDetector {

	/**
	 * Source trees where {@code .evaluate()} and {@code .toDouble()} in computation code
	 * are flagged as Producer pattern violations.
	 */
	private static final List<String> COMPUTATION_SOURCE_TREES = List.of(
			"/ml/src/main/java/",
			"/studio/"
	);

	/**
	 * Methods where {@code .evaluate()} calls are legitimate despite being in
	 * a computation source tree. These are autoregressive step loops or other
	 * boundaries where the compiled model is invoked token-by-token.
	 */
	private static final List<String> EVALUATE_ALLOWED_METHODS = List.of(
			"runGruDecode",             // Autoregressive GRU decode loop (step boundaries)
			"embedAndSumNet"            // SkyTntMidi step-boundary: materializes the summed
			                            // token embedding fed into netCompiledModel.forward
	);

	/**
	 * Files in computation source trees where {@code .evaluate()} is legitimate.
	 * These are pipeline boundaries, data loading, or sampling loops.
	 */
	private static final List<String> EVALUATE_ALLOWED_FILES = List.of(
			"DiffusionSampler.java",            // Step boundary in sampling loop
			"DiffusionFeatures.java",           // Diffusion setup
			"DiffusionTrainingDataset.java",    // Data loading boundary
			"AudioScene.java",                  // Pipeline boundary
			"AudioDiffusionGenerator.java",     // Step boundaries
			"AudioGenerator.java",              // Pipeline boundary
			"AudioModulator.java",              // Pipeline boundary
			"AudioTrainingDataCollector.java",  // Data loading boundary
			"AudioLatentDataset.java",          // Data loading boundary
			"AutoEncoderFeatureProvider.java",  // Pipeline boundary
			"DefaultChannelSectionFactory.java", // Configuration
			"ChordProgressionManager.java",     // Heredity domain
			"ParameterSet.java",                // Heredity domain
			"PatternFeatures.java",             // Pattern setup
			"LegacyAudioGenerator.java",        // Legacy code
			// TODO Temporary exemptions pending AudioScene rendering redesign.
			// These classes participate in the current AudioScene render flow
			// and their Producer-pattern violations are tracked for rework on
			// the rendering-redesign branch.
			"ParameterizedFilterEnvelope.java",
			"ParameterizedLayerEnvelope.java",
			"ParameterizedVolumeEnvelope.java",
			"PatternLayerManager.java",
			"GridSequencer.java"
	);

	/**
	 * Files in computation source trees where {@code .toDouble()} is legitimate.
	 */
	private static final List<String> TODOUBLE_ALLOWED_FILES = List.of(
			"AudioMeter.java",                  // Monitoring/metrics
			"ConditionalAudioScoring.java",     // Loss/metric at pipeline boundary
			"ChordProgressionManager.java",     // Heredity domain
			"ParameterSet.java",                // Heredity domain
			"PatternLayerManager.java",         // Automation parameter
			// TODO Temporary exemptions pending AudioScene rendering redesign.
			"ParameterizedFilterEnvelope.java",
			"ParameterizedVolumeEnvelope.java",
			"ScaleTraversalStrategy.java",
			"GridSequencer.java"
	);

	/**
	 * Per-method exemptions for {@code .evaluate()}, keyed by file name. This is
	 * preferred over whole-file exemptions in {@link #EVALUATE_ALLOWED_FILES} when
	 * only specific methods of a class act as pipeline boundaries. The set value
	 * is the method names exempted within that file.
	 */
	private static final Map<String, Set<String>> EVALUATE_ALLOWED_FILE_METHODS = Map.of(
			"CompiledModelAutoEncoder.java", Set.of(
					"encode",                   // Producer / CompiledModel.forward boundary
					"decode"                    // Producer / CompiledModel.forward boundary
			),
			"ConditionalAudioScoring.java", Set.of(
					"computeDenoisingScore",    // Scoring method returning double; boundary
					"computeReconstructionScore", // Scoring method returning double; boundary
					"computeScore"              // Scoring method returning double; boundary
			),
			"AutoregressiveModel.java", Set.of(
					"sampleToken",              // Token-selection at autoregressive step boundary
					"of"                        // Step-sampler lambda in static factory
			),
			"CompoundMidiEmbedding.java", Set.of(
					"embed",                    // Materializes Java MidiCompoundToken for type dispatch
					"embedSequence"             // Materializes Java token list for Producer construction
			)
	);

	/**
	 * Per-method exemptions for {@code .toDouble()}, keyed by file name. Mirrors
	 * {@link #EVALUATE_ALLOWED_FILE_METHODS} for {@code .toDouble()} calls.
	 */
	private static final Map<String, Set<String>> TODOUBLE_ALLOWED_FILE_METHODS = Map.of(
			"AutoregressiveModel.java", Set.of(
					"getTemperature",           // Accessor for temperature scalar
					"sampleToken",              // Token-selection at autoregressive step boundary
					"of"                        // Step-sampler lambda in static factory
			),
			"DiffusionNoiseScheduler.java", Set.of(
					"addNoise",                 // Schedule-table scalar lookup at step boundary
					"step",                     // DDPM step-boundary scalar lookups
					"stepDDIM",                 // DDIM step-boundary scalar lookups
					"getAlphaCumprod"           // Single-scalar schedule accessor
			),
			"SkyTntMidi.java", Set.of(
					"applyMask",                // Token-selection orchestration (post-model)
					"sampleWithTopK"            // Token-selection orchestration (post-model)
			),
			"AudioSceneOptimizer.java", Set.of(
					"defaultBreeder"            // Heredity-domain genome perturbation
			),
			"SampleBrush.java", Set.of(
					"addFrameValues"            // Spatial visualization data iteration
			),
			"EditableSpatialWaveDetails.java", Set.of(
					"applyValues"               // Spatial visualization edit application
			)
	);

	/** Detects {@code .evaluate()} calls in source code. */
	private static final Pattern EVALUATE_CALL = Pattern.compile(
			"\\.evaluate\\s*\\("
	);

	/** Detects {@code .toDouble()} calls in source code. */
	private static final Pattern TODOUBLE_CALL = Pattern.compile(
			"\\.toDouble\\s*\\("
	);

	/**
	 * Creates a detector that will scan Java source files under the given directory.
	 *
	 * @param rootDir  the root directory to scan
	 */
	public ProducerPatternDetector(Path rootDir) {
		super(rootDir);
	}

	/**
	 * Scans a single file for Producer pattern violations.
	 *
	 * @param file  the file to scan
	 * @return this detector for chaining
	 */
	@Override
	public ProducerPatternDetector scanFile(Path file) {
		try {
			List<String> lines = Files.readAllLines(file);
			checkProducerPatternViolations(file, lines);
		} catch (IOException e) {
			warn("Could not read file " + file, e);
		}

		return this;
	}

	/**
	 * Checks for {@code .evaluate()} and {@code .toDouble()} calls in computation source trees.
	 *
	 * <p>These calls break the computation graph when used inside model layers.
	 * They are only acceptable at pipeline boundaries (test methods, main methods,
	 * autoregressive loop boundaries, data loading).</p>
	 *
	 * @param file     the file being checked
	 * @param lines    the file content split into lines
	 */
	private void checkProducerPatternViolations(Path file, List<String> lines) {
		String pathStr = file.toString();

		// Only check files in computation source trees
		boolean inComputationTree = false;
		for (String tree : COMPUTATION_SOURCE_TREES) {
			if (pathStr.contains(tree)) {
				inComputationTree = true;
				break;
			}
		}
		if (!inComputationTree) return;

		String fileName = file.getFileName().toString();

		// Determine if .evaluate() calls are allowed in this file
		boolean evaluateAllowed = false;
		for (String allowed : EVALUATE_ALLOWED_FILES) {
			if (fileName.equals(allowed)) {
				evaluateAllowed = true;
				break;
			}
		}

		// Determine if .toDouble() calls are allowed in this file
		boolean toDoubleAllowed = false;
		for (String allowed : TODOUBLE_ALLOWED_FILES) {
			if (fileName.equals(allowed)) {
				toDoubleAllowed = true;
				break;
			}
		}

		// Per-method exemptions scoped to this file (finer-grained than whole-file).
		Set<String> fileEvaluateMethods = EVALUATE_ALLOWED_FILE_METHODS.getOrDefault(fileName, Set.of());
		Set<String> fileToDoubleMethods = TODOUBLE_ALLOWED_FILE_METHODS.getOrDefault(fileName, Set.of());

		boolean inJavadoc = false;

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String trimmedLine = line.trim();
			int lineNum = i + 1;

			if (trimmedLine.startsWith("/**")) inJavadoc = true;
			if (trimmedLine.contains("*/")) { inJavadoc = false; continue; }
			if (inJavadoc || trimmedLine.startsWith("*") || trimmedLine.startsWith("//")) {
				continue;
			}

			// Check enclosing method — main methods, constructors, and allowed autoregressive
			// step methods are exempt
			String methodName = findEnclosingMethodName(lines, i);
			if (methodName.equals("main") || methodName.equals("<constructor>")) {
				continue;
			}

			boolean inAllowedMethod = EVALUATE_ALLOWED_METHODS.contains(methodName);
			boolean evaluateMethodExempt = fileEvaluateMethods.contains(methodName);
			boolean toDoubleMethodExempt = fileToDoubleMethods.contains(methodName);

			if (!evaluateAllowed && !inAllowedMethod && !evaluateMethodExempt
					&& EVALUATE_CALL.matcher(line).find()) {
				violations.add(new Violation(file, lineNum, line,
						"PRODUCER_EVALUATE_IN_COMPUTATION",
						".evaluate() inside computation code breaks the computation graph. " +
								"Return a CollectionProducer instead and let the caller evaluate at the pipeline boundary."));
			}

			if (!toDoubleAllowed && !toDoubleMethodExempt && TODOUBLE_CALL.matcher(line).find()) {
				violations.add(new Violation(file, lineNum, line,
						"PRODUCER_TODOUBLE_IN_COMPUTATION",
						".toDouble() in computation code pulls data to host via JNI. " +
								"Use Producer operations (.multiply(), .add(), etc.) to keep computation on the device."));
			}
		}
	}
}
