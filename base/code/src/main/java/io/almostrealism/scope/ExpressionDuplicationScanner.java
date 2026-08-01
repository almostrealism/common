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

package io.almostrealism.scope;

import io.almostrealism.expression.Expression;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read-only diagnostic that walks a set of {@link Expression} roots and reports
 * how much structural duplication exists in the reachable graph.
 *
 * <p>The scanner answers the question "how many of the live Expression nodes
 * would collapse to a single canonical instance if every constructor were
 * hash-consed?" without changing, rewriting, or interning any node. It is safe
 * to call on any in-use graph.</p>
 *
 * <h2>What gets counted</h2>
 * <p>Two equivalence relations are tracked side-by-side:</p>
 * <ul>
 *   <li><b>Identity</b> — the same {@link Expression} instance reached from two
 *       different positions in the walk. The scanner skips re-visits via an
 *       {@link IdentityHashMap}, so each identity-shared node is counted once,
 *       not once per reference. Identity sharing is not a duplicate; the memory
 *       is already shared.</li>
 *   <li><b>Structural equality</b> — two distinct-by-identity nodes for which
 *       {@link Expression#equals(Object)} returns {@code true}. The scanner
 *       counts these via a regular {@link HashMap} and reports them as the
 *       duplicate population. These are the nodes interning would collapse.</li>
 * </ul>
 *
 * <h2>Output</h2>
 * <p>The {@link Report} returned by {@link #scan(Collection)} carries:</p>
 * <ul>
 *   <li>Total unique-by-identity node count.</li>
 *   <li>Distinct-by-equals canonical class count.</li>
 *   <li>Per-subclass breakdown of both numbers (which subclasses have the most
 *       redundant copies).</li>
 *   <li>Per-{@link Expression#treeDepth()} breakdown (whether duplication
 *       concentrates at the leaves or in deeper sub-trees).</li>
 * </ul>
 *
 * <p>The scanner makes no claim about <em>where in the workload</em> the
 * duplication came from — the caller is responsible for snapshotting the graph
 * at a meaningful point (typically after {@code Process.optimize()}).</p>
 */
public class ExpressionDuplicationScanner {

	/**
	 * Static-only utility; instances are not meaningful.
	 */
	private ExpressionDuplicationScanner() {
	}

	/**
	 * Walks the given roots and returns a duplication report.
	 *
	 * <p>Null roots are skipped. Null children (if any subclass returns them)
	 * are also skipped. The walk is iterative to avoid stack pressure on deep
	 * expression trees.</p>
	 *
	 * @param roots one or more expression roots; may be empty
	 * @return the duplication report
	 */
	public static Report scan(Collection<? extends Expression<?>> roots) {
		IdentityHashMap<Expression<?>, Boolean> visited = new IdentityHashMap<>();
		HashMap<Expression<?>, Expression<?>> canonical = new HashMap<>();
		Map<String, ClassStats> byClass = new HashMap<>();
		Map<Integer, DepthStats> byDepth = new TreeMap<>();
		ArrayDeque<Expression<?>> stack = new ArrayDeque<>();

		for (Expression<?> root : roots) {
			if (root != null) stack.push(root);
		}

		while (!stack.isEmpty()) {
			Expression<?> e = stack.pop();
			if (visited.put(e, Boolean.TRUE) != null) continue;

			String name = e.getClass().getSimpleName();
			int depth = e.treeDepth();

			ClassStats cls = byClass.computeIfAbsent(name, k -> new ClassStats());
			DepthStats dpt = byDepth.computeIfAbsent(depth, k -> new DepthStats());
			cls.totalNodes++;
			dpt.totalNodes++;

			Expression<?> existing = canonical.putIfAbsent(e, e);
			if (existing == null) {
				cls.distinctNodes++;
				dpt.distinctNodes++;
			}

			List<Expression<?>> children = e.getChildren();
			if (children != null) {
				for (Expression<?> child : children) {
					if (child != null) stack.push(child);
				}
			}
		}

		return new Report(visited.size(), canonical.size(), byClass, byDepth);
	}

	/**
	 * Convenience overload for a single root.
	 *
	 * @param root the expression to scan
	 * @return the duplication report
	 */
	public static Report scan(Expression<?> root) {
		return scan(List.of(root));
	}

	/**
	 * Aggregate result of a single scan.
	 *
	 * <p>All counts refer to <em>unique-by-identity</em> nodes reachable from the
	 * scanned roots. The duplication ratio is the fraction of those nodes that
	 * would collapse onto an existing canonical instance under hash-consing.</p>
	 */
	public static class Report {
		/** Total unique-by-identity node count reached from the scanned roots. */
		private final long totalNodes;
		/** Distinct-by-{@code equals} canonical class count within {@link #totalNodes}. */
		private final long distinctNodes;
		/** Per-{@code Expression} subclass breakdown. */
		private final Map<String, ClassStats> byClass;
		/** Per-{@link Expression#treeDepth()} breakdown. */
		private final Map<Integer, DepthStats> byDepth;

		/**
		 * Package-private constructor used by {@link ExpressionDuplicationScanner#scan(Collection)}.
		 * Defensive copies are taken of both per-class and per-depth maps.
		 *
		 * @param totalNodes    total unique-by-identity nodes reached
		 * @param distinctNodes distinct-by-{@code equals} canonical class count
		 * @param byClass       per-subclass breakdown to copy
		 * @param byDepth       per-depth breakdown to copy
		 */
		Report(long totalNodes, long distinctNodes,
				Map<String, ClassStats> byClass,
				Map<Integer, DepthStats> byDepth) {
			this.totalNodes = totalNodes;
			this.distinctNodes = distinctNodes;
			this.byClass = Collections.unmodifiableMap(new HashMap<>(byClass));
			this.byDepth = Collections.unmodifiableMap(new TreeMap<>(byDepth));
		}

		/**
		 * Returns the total number of unique-by-identity nodes reached from the
		 * scanned roots.
		 *
		 * @return the total reachable node count
		 */
		public long getTotalNodes() { return totalNodes; }

		/**
		 * Returns the number of distinct-by-{@code equals} equivalence classes
		 * among the reached nodes.
		 *
		 * @return the canonical class count
		 */
		public long getDistinctNodes() { return distinctNodes; }

		/**
		 * Returns the number of nodes that would be eliminated by interning.
		 *
		 * @return {@code totalNodes - distinctNodes}
		 */
		public long getDuplicateNodes() { return totalNodes - distinctNodes; }

		/**
		 * Returns the duplication ratio, i.e. the fraction of reached nodes that
		 * are redundant copies of an already-counted canonical class. Zero means
		 * every node is unique by equals; values closer to one mean heavy
		 * structural redundancy.
		 *
		 * @return the duplication ratio in {@code [0.0, 1.0)}
		 */
		public double duplicationRatio() {
			return totalNodes == 0 ? 0.0 : 1.0 - distinctNodes / (double) totalNodes;
		}

		/**
		 * Returns the per-subclass breakdown of the scan.
		 *
		 * @return an unmodifiable map keyed by {@code Class#getSimpleName}
		 */
		public Map<String, ClassStats> getByClass() { return byClass; }

		/**
		 * Returns the per-{@link Expression#treeDepth()} breakdown of the scan.
		 *
		 * @return an unmodifiable map keyed by tree depth
		 */
		public Map<Integer, DepthStats> getByDepth() { return byDepth; }

		/**
		 * Returns a one-line summary of the scan.
		 *
		 * @return a compact summary string
		 */
		public String summary() {
			return String.format("total=%d distinct=%d duplicates=%d ratio=%.3f",
					totalNodes, distinctNodes, getDuplicateNodes(), duplicationRatio());
		}

		/**
		 * Returns a multi-line table with the per-class and per-depth breakdowns
		 * sorted by total node count descending (class) and depth ascending.
		 *
		 * @return a human-readable table for logging
		 */
		public String fullTable() {
			StringBuilder sb = new StringBuilder();
			sb.append(summary()).append('\n');
			sb.append("by class:\n");
			byClass.entrySet().stream()
					.sorted(Comparator.comparingLong((Map.Entry<String, ClassStats> e) ->
							e.getValue().totalNodes).reversed())
					.forEach(e -> sb.append(String.format(
							"  %-32s total=%d distinct=%d dup=%d%n",
							e.getKey(), e.getValue().totalNodes,
							e.getValue().distinctNodes,
							e.getValue().totalNodes - e.getValue().distinctNodes)));
			sb.append("by depth:\n");
			byDepth.entrySet().stream()
					.forEach(e -> sb.append(String.format(
							"  depth=%-4d total=%d distinct=%d dup=%d%n",
							e.getKey(), e.getValue().totalNodes,
							e.getValue().distinctNodes,
							e.getValue().totalNodes - e.getValue().distinctNodes)));
			return sb.toString();
		}
	}

	/**
	 * Per-subclass tally. Mutable during a scan; immutable from the caller's
	 * perspective once the {@link Report} is returned.
	 */
	public static class ClassStats {
		/** Total unique-by-identity nodes of this subclass reached during the scan. */
		long totalNodes;
		/** Distinct-by-{@code equals} canonical class count within this subclass. */
		long distinctNodes;

		/**
		 * Returns the total number of unique-by-identity nodes of this subclass.
		 *
		 * @return total node count for this subclass
		 */
		public long getTotalNodes() { return totalNodes; }

		/**
		 * Returns the number of distinct-by-{@code equals} canonical classes
		 * within this subclass.
		 *
		 * @return distinct canonical count for this subclass
		 */
		public long getDistinctNodes() { return distinctNodes; }
	}

	/**
	 * Per-depth tally. Mutable during a scan; immutable from the caller's
	 * perspective once the {@link Report} is returned.
	 */
	public static class DepthStats {
		/** Total unique-by-identity nodes at this tree depth reached during the scan. */
		long totalNodes;
		/** Distinct-by-{@code equals} canonical class count at this tree depth. */
		long distinctNodes;

		/**
		 * Returns the total number of unique-by-identity nodes at this tree depth.
		 *
		 * @return total node count at this depth
		 */
		public long getTotalNodes() { return totalNodes; }

		/**
		 * Returns the number of distinct-by-{@code equals} canonical classes at
		 * this tree depth.
		 *
		 * @return distinct canonical count at this depth
		 */
		public long getDistinctNodes() { return distinctNodes; }
	}
}
