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

import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.profile.OperationSource;
import org.almostrealism.io.TimingMetric;
import org.almostrealism.util.TestSuiteBase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared fixture for the {@code org.almostrealism.ui} tests: builds a small,
 * fully deterministic {@link OperationProfileNode} tree with known timings,
 * metadata and compiled sources so that every derived value (durations,
 * percentages, tree shapes, labels) can be asserted exactly.
 *
 * <p>The profile has the following shape (self durations in seconds):</p>
 * <pre>
 * Root (k-root, no metric, holds metadata and operation sources)
 * +-- MatMul    (k-matmul)  self 2.0  = 0.5 compile (1 call) + 1.5 run (3 calls), compiled
 * |   +-- Inner Add (k-inner) self 0.25 run
 * +-- Softmax   (k-softmax) self 0.75 run, measured 0.0004
 * +-- Copy      (k-copy)    no metric, compiled (source is null)
 * </pre>
 */
public abstract class OperationProfileTestBase extends TestSuiteBase {
	/** Key of the root node. */
	protected static final String ROOT_KEY = "k-root";
	/** Key of the compiled matrix-multiply node. */
	protected static final String MATMUL_KEY = "k-matmul";
	/** Key of the grandchild beneath the matrix-multiply node. */
	protected static final String INNER_KEY = "k-inner";
	/** Key of the softmax node (not compiled, has a measured time). */
	protected static final String SOFTMAX_KEY = "k-softmax";
	/** Key of the copy node (compiled, but its source text is null). */
	protected static final String COPY_KEY = "k-copy";
	/** An argument key that does not correspond to any node in the profile. */
	protected static final String MISSING_KEY = "k-missing";

	/** Generated kernel attached to the matrix-multiply node: 16 lines, 3 functions. */
	protected static final String KERNEL_SOURCE = String.join("\n",
			"void f_add_1(double *a) {",
			"for (int i = 0; i < 4; i++) {",
			"a[i] = sin(a[i]) + cos(a[i]);",
			"}",
			"}",
			"void f_add_2(double *a) {",
			"for (int i = 0; i < 4; i++) {",
			"a[i] = sin(a[i]) + cos(a[i]);",
			"}",
			"}",
			"double f_mul_3(double x) {",
			"if (x > 0) {",
			"return pow(x, 2.0) * exp(x);",
			"}",
			"return fabs(x);",
			"}");

	/**
	 * Builds the profile described in the class documentation.
	 *
	 * @return the root of a freshly constructed profile tree
	 */
	protected OperationProfileNode sampleProfile() {
		OperationProfileNode inner = node(INNER_KEY, "Inner Add");
		inner.setMetric(metric("x run", 0.25));

		OperationProfileNode matmul = node(MATMUL_KEY, "MatMul");
		TimingMetric matmulMetric = metric("k1 compile", 0.5);
		for (int i = 0; i < 3; i++) {
			matmulMetric.addEntry("k1 run", 0.5);
		}
		matmul.setMetric(matmulMetric);
		TimingMetric stageMetric = metric("parse", 0.1);
		stageMetric.addEntry("codegen", 0.2);
		matmul.setStageDetailTime(stageMetric);
		matmul.setChildren(new ArrayList<>(List.of(inner)));

		OperationProfileNode softmax = node(SOFTMAX_KEY, "Softmax");
		softmax.setMetric(metric("y run", 0.75));
		softmax.setMeasuredTime(metric("m", 0.0004));

		OperationProfileNode copy = node(COPY_KEY, "Copy");

		OperationProfileNode root = node(ROOT_KEY, "Root");
		root.setChildren(new ArrayList<>(List.of(matmul, softmax, copy)));

		Map<String, String> metadata = new HashMap<>();
		metadata.put(MATMUL_KEY, "MatMul (64x64)");
		metadata.put(SOFTMAX_KEY, "Softmax detail");
		metadata.put(INNER_KEY, "Add detail");
		root.setMetadata(metadata);

		Map<String, List<OperationSource>> sources = new HashMap<>();
		sources.put(MATMUL_KEY, new ArrayList<>(List.of(new OperationSource(KERNEL_SOURCE,
				new ArrayList<>(List.of(SOFTMAX_KEY, INNER_KEY, MISSING_KEY)),
				new ArrayList<>(List.of("arg0", "arg1", "arg2"))))));
		sources.put("Softmax_kernel", new ArrayList<>(List.of(new OperationSource("s0\ns1",
				new ArrayList<>(), new ArrayList<>()))));
		sources.put("Inner Add", new ArrayList<>(List.of(new OperationSource("inner code",
				new ArrayList<>(List.of(MATMUL_KEY)), new ArrayList<>(List.of("in"))))));
		sources.put(COPY_KEY, new ArrayList<>(List.of(new OperationSource())));
		root.setOperationSources(sources);
		return root;
	}

	/**
	 * Creates a timing metric holding a single recorded entry, so that its
	 * entries and counts are consistent exactly as they are in recorded profiles.
	 *
	 * @param key   the entry key
	 * @param value the duration in seconds
	 * @return the new metric
	 */
	protected static TimingMetric metric(String key, double value) {
		TimingMetric metric = new TimingMetric();
		metric.addEntry(key, value);
		return metric;
	}

	/**
	 * Creates a profile node with the given key and name and no timing data.
	 *
	 * @param key  the node key
	 * @param name the node display name
	 * @return the new node
	 */
	protected static OperationProfileNode node(String key, String name) {
		OperationProfileNode node = new OperationProfileNode(name);
		node.setKey(key);
		return node;
	}
}
