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

import io.almostrealism.expression.Constant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide canonicalisation table for immutable leaf {@link Expression}
 * instances.
 *
 * <h2>What the table does</h2>
 * <p>When the leaf-interning flag
 * ({@link ScopeSettings#enableLeafInterning}) is enabled, every parent
 * {@link Expression} canonicalises its children array via
 * {@link #canonicalize(Expression[])} at construction time. The canonical
 * lookup is content-keyed (uses {@link Expression#equals(Object)} and
 * {@link Expression#hashCode()}, which short-circuit on cached structural
 * metrics), so two freshly-allocated leaves with identical value collapse to
 * a single shared reference in their parents.</p>
 *
 * <p>Result: the freshly-allocated duplicates become unreferenced as soon as
 * their parents finish construction, and the framework's live set shrinks to
 * one canonical instance per distinct leaf value. Allocation throughput is
 * unchanged; long-lived retention is reduced.</p>
 *
 * <h2>What is "internable"</h2>
 * <p>The table targets immutable leaves; canonicalisation safety is verified
 * per-class because the framework's structural {@code compare()} contract is
 * not always strict enough to use as an intern key directly:</p>
 * <ul>
 *   <li>{@link Constant} subclasses: {@code IntegerConstant},
 *       {@code DoubleConstant}, {@code LongConstant},
 *       {@code BooleanConstant}, {@code ConstantValue}. Pure value-types
 *       whose {@code compare()} fully captures the value.</li>
 *   <li>{@link InstanceReference}: its existing {@code compare()} already
 *       includes the referent {@link io.almostrealism.scope.Variable} and the
 *       optional position and index expressions, so it is safe out of the
 *       box.</li>
 *   <li>{@link KernelIndex}: the framework's loose {@code compare()}
 *       intentionally ignores the bound context so that
 *       {@code getIndexOptions(kernel())} can match a context-bearing
 *       index inside an expression against a no-context query. Using
 *       that loose compare as the intern key would collapse contexts
 *       together. The table therefore uses a strict secondary key
 *       (axis + context-by-reference) for {@code KernelIndex} only,
 *       routed through {@link #kernelIndexTable} below. Expression-level
 *       equality stays unchanged.</li>
 * </ul>
 *
 * <p>{@code SizeValue} and other context-bearing leaves remain excluded
 * until their {@code compare()} contracts are similarly audited.</p>
 *
 * <h2>Bounding</h2>
 * <p>The table is bounded by {@link ScopeSettings#maxLeafInternTableSize}.
 * When full, lookups still return the existing canonical instance if one is
 * present, but new entries are not added — i.e. behaviour degrades to
 * "intern what we already saw, leave new things alone." This avoids
 * unbounded memory growth without a separate eviction policy.</p>
 *
 * <h2>Threading</h2>
 * <p>The backing {@link ConcurrentHashMap} is thread-safe; multiple threads
 * may call {@link #canonical(Expression)} concurrently. Two threads racing on
 * the same value may both produce a fresh leaf, but only one will be the
 * canonical winner — the loser's leaf becomes garbage as soon as its caller
 * finishes.</p>
 */
public class LeafInternTable {

	/** Backing table for {@link Constant} and {@link InstanceReference}; canonical instance stored as the value for its own key. */
	private static final ConcurrentHashMap<Expression<?>, Expression<?>> table = new ConcurrentHashMap<>();

	/**
	 * Strict secondary table for {@link KernelIndex} keyed by axis + context
	 * (see {@link KernelIndexKey}). Necessary because {@code KernelIndex}'s
	 * structural {@code compare()} intentionally ignores context.
	 */
	private static final ConcurrentHashMap<KernelIndexKey, KernelIndex> kernelIndexTable = new ConcurrentHashMap<>();

	/**
	 * Static-only utility; instances are not meaningful.
	 */
	private LeafInternTable() {
	}

	/**
	 * Returns the canonical instance for the given expression if it is
	 * eligible for interning, otherwise returns the input unchanged.
	 *
	 * <p>When {@link ScopeSettings#enableLeafInterning} is {@code false} this
	 * method is a no-op and returns the input as-is, including for
	 * {@code null} inputs.</p>
	 *
	 * <p>The result is typed as {@code Expression<?>} rather than the input's
	 * own type parameter because the canonical instance is shared across all
	 * call sites; the entry was registered under whatever generic instantiation
	 * was first to install it. The interning contract guarantees the returned
	 * instance is structurally equal to {@code e}, so erased usage is safe.</p>
	 *
	 * @param e the expression to canonicalise; may be {@code null}
	 * @return the canonical instance if one exists, otherwise {@code e}
	 */
	public static Expression<?> canonical(Expression<?> e) {
		if (!ScopeSettings.enableLeafInterning) return e;
		if (e == null) return null;
		if (e instanceof KernelIndex) return canonicalKernelIndex((KernelIndex) e);
		if (!isInternable(e)) return e;

		if (table.size() >= ScopeSettings.maxLeafInternTableSize) {
			Expression<?> existing = table.get(e);
			return existing != null ? existing : e;
		}

		Expression<?> existing = table.putIfAbsent(e, e);
		return existing != null ? existing : e;
	}

	/**
	 * Looks up the canonical instance for a {@link KernelIndex} in the strict
	 * secondary table keyed by axis + context. Bypasses {@link #table} and the
	 * loose {@link KernelIndex#compare(Expression)} contract.
	 *
	 * @param idx the kernel index to canonicalise
	 * @return the canonical instance for the (axis, context) pair, or the
	 *         input when the table cap is reached and no entry exists yet
	 */
	private static KernelIndex canonicalKernelIndex(KernelIndex idx) {
		KernelIndexKey key = new KernelIndexKey(idx);

		if (kernelIndexTable.size() >= ScopeSettings.maxLeafInternTableSize) {
			KernelIndex existing = kernelIndexTable.get(key);
			return existing != null ? existing : idx;
		}

		KernelIndex existing = kernelIndexTable.putIfAbsent(key, idx);
		return existing != null ? existing : idx;
	}

	/**
	 * Returns a children array with every internable element replaced by its
	 * canonical instance. The input array is returned unchanged when nothing
	 * needs replacing, so callers that just want to forward the result do not
	 * pay for a defensive copy.
	 *
	 * <p>When {@link ScopeSettings#enableLeafInterning} is {@code false} this
	 * method is a no-op.</p>
	 *
	 * @param children the children array; may be {@code null} or empty
	 * @return the canonicalised array, or {@code children} itself if no
	 *         element required canonicalisation
	 */
	public static Expression<?>[] canonicalize(Expression<?>[] children) {
		if (!ScopeSettings.enableLeafInterning) return children;
		if (children == null || children.length == 0) return children;

		Expression<?>[] result = null;
		for (int i = 0; i < children.length; i++) {
			Expression<?> c = children[i];
			Expression<?> canon = canonical(c);
			if (canon != c) {
				if (result == null) {
					result = new Expression<?>[children.length];
					System.arraycopy(children, 0, result, 0, children.length);
				}
				result[i] = canon;
			}
		}
		return result != null ? result : children;
	}

	/**
	 * Reports whether the given expression is recognised as internable.
	 *
	 * <p>Covers {@link Constant} subclasses (pure value-types),
	 * {@link InstanceReference} (whose existing {@code compare()} captures
	 * all of var/pos/index), and {@link KernelIndex} (which is routed through
	 * the strict secondary table because its structural {@code compare()}
	 * intentionally ignores context).</p>
	 *
	 * @param e the expression to check; may be {@code null}
	 * @return {@code true} if {@code e} is a non-null recognised leaf type
	 */
	public static boolean isInternable(Expression<?> e) {
		return e instanceof Constant
				|| e instanceof InstanceReference
				|| e instanceof KernelIndex;
	}

	/**
	 * Returns the total number of canonical entries currently held across both
	 * the primary {@link Constant}/{@link InstanceReference} table and the
	 * strict {@link KernelIndex} table. Intended for tests and diagnostic
	 * logging only; the count may change concurrently.
	 *
	 * @return the current combined table size
	 */
	public static int size() {
		return table.size() + kernelIndexTable.size();
	}

	/**
	 * Removes all entries from both tables. Intended for tests so they can run
	 * with a clean baseline.
	 */
	public static void clear() {
		table.clear();
		kernelIndexTable.clear();
	}

	/**
	 * Strict intern key for {@link KernelIndex} composed of the dispatch axis
	 * and the bound context. Context is compared by reference because none of
	 * the standard {@link KernelStructureContext} implementations override
	 * {@code equals} — that is what keeps two {@code KernelIndex} instances on
	 * the same axis but different contexts from collapsing into one canonical
	 * entry.
	 */
	private static final class KernelIndexKey {
		/** Dispatch axis. */
		private final int axis;
		/** Bound context; may be {@code null} for an unbound placeholder index. */
		private final KernelStructureContext context;

		/**
		 * Captures the strict identity of a {@link KernelIndex}.
		 *
		 * @param idx the kernel index whose strict identity to capture
		 */
		KernelIndexKey(KernelIndex idx) {
			this.axis = idx.getKernelAxis();
			this.context = idx.getStructureContext();
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof KernelIndexKey)) return false;
			KernelIndexKey other = (KernelIndexKey) o;
			return axis == other.axis && Objects.equals(context, other.context);
		}

		@Override
		public int hashCode() {
			return axis * 31 + Objects.hashCode(context);
		}
	}
}
