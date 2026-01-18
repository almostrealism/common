/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.time;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

/**
 * A deprecated pair data structure that stores two cursor positions for temporal operations.
 *
 * <p>{@link CursorPair} extends {@link Pair} to provide named accessor methods for cursor and
 * delay cursor values. It was historically used for tracking position state in time-series
 * operations and temporal computations.</p>
 *
 * <h2>Deprecation Notice</h2>
 * <p>This class is deprecated and maintained only for backward compatibility. Modern code should:</p>
 * <ul>
 *   <li>Use {@link Pair} directly for simple cursor pairs</li>
 *   <li>Use explicit cursor management in {@link AcceleratedTimeSeries}</li>
 *   <li>Use {@link TemporalScalar} for time-value pairs</li>
 * </ul>
 *
 * <h2>Structure</h2>
 * <pre>
 * CursorPair inherits from Pair:
 * - A (cursor): Primary cursor position
 * - B (delayCursor): Secondary/delay cursor position
 * </pre>
 *
 * <h2>Legacy Usage</h2>
 *
 * <h3>Basic Creation</h3>
 * <pre>{@code
 * // Default: both cursors at 0
 * CursorPair pair = new CursorPair();
 *
 * // With specific values
 * CursorPair pair = new CursorPair(10.0, 20.0);
 * }</pre>
 *
 * <h3>Cursor Access</h3>
 * <pre>{@code
 * CursorPair pair = new CursorPair(5.0, 10.0);
 * double cursor = pair.getCursor();       // 5.0
 * double delay = pair.getDelayCursor();   // 10.0
 *
 * pair.setCursor(7.0);
 * pair.setDelayCursor(15.0);
 * }</pre>
 *
 * <h3>Delay Cursor Constraint</h3>
 * <p>The {@link #setDelayCursor(double)} method enforces that delayCursor > cursor.
 * If a value less than or equal to cursor is set, it's automatically adjusted to cursor + 1:</p>
 * <pre>{@code
 * CursorPair pair = new CursorPair(10.0, 20.0);
 * pair.setDelayCursor(5.0);  // Too small
 * System.out.println(pair.getDelayCursor());  // 11.0 (cursor + 1)
 * }</pre>
 *
 * <h3>Hardware-Accelerated Increment</h3>
 * <pre>{@code
 * CursorPair pair = new CursorPair(0.0, 1.0);
 * Producer<PackedCollection> delta = c(p(new Pair(1.0, 0.0)));
 *
 * // Create increment operation
 * Supplier<Runnable> op = pair.increment(delta);
 * op.get().run();  // Increments both cursors by delta
 * }</pre>
 *
 * <h2>Migration Guide</h2>
 *
 * <h3>Replace with Pair</h3>
 * <pre>{@code
 * // OLD (deprecated)
 * CursorPair cursors = new CursorPair(10.0, 20.0);
 * double cursor = cursors.getCursor();
 *
 * // NEW (recommended)
 * Pair cursors = new Pair(10.0, 20.0);
 * double cursor = cursors.getA();
 * }</pre>
 *
 * <h3>Time-Series Cursor Management</h3>
 * <pre>{@code
 * // OLD (using CursorPair)
 * CursorPair cursors = new CursorPair(0.0, 0.0);
 * cursors.setCursor(series.getEndCursorIndex());
 *
 * // NEW (direct cursor management)
 * AcceleratedTimeSeries series = new AcceleratedTimeSeries(1024);
 * int cursor = series.getEndCursorIndex();
 * }</pre>
 *
 * @deprecated This class is no longer recommended. Use {@link Pair}, direct cursor fields,
 *             or time-series cursor management methods instead.
 *
 * @see Pair
 * @see TemporalScalar
 * @see AcceleratedTimeSeries
 *
 * @author Michael Murray
 */
@Deprecated
public class CursorPair extends Pair {
	/**
	 * Constructs a cursor pair with both cursors initialized to zero.
	 */
	public CursorPair() { super(0, 0); }

	/**
	 * Constructs a cursor pair with the specified cursor values.
	 *
	 * @param cursor The primary cursor position
	 * @param delayCursor The secondary/delay cursor position
	 */
	public CursorPair(double cursor, double delayCursor) { super(cursor, delayCursor); }

	/**
	 * Sets the primary cursor position.
	 *
	 * @param v The new cursor value
	 */
	public void setCursor(double v) { setA(v); }

	/**
	 * Returns the primary cursor position.
	 *
	 * @return The current cursor value
	 */
	public double getCursor() { return getA(); }

	/**
	 * Sets the delay cursor position with automatic constraint enforcement.
	 *
	 * <p>This method ensures that the delay cursor is always strictly greater than
	 * the primary cursor. If the specified value is less than or equal to the cursor,
	 * it is automatically adjusted to {@code cursor + 1}.</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * CursorPair pair = new CursorPair(10.0, 20.0);
	 * pair.setDelayCursor(5.0);  // Less than cursor (10.0)
	 * System.out.println(pair.getDelayCursor());  // 11.0 (auto-adjusted)
	 * }</pre>
	 *
	 * @param v The desired delay cursor value (may be adjusted)
	 */
	public void setDelayCursor(double v) {
		setB(v);
		if (getDelayCursor() <= getCursor()) setDelayCursor(getCursor() + 1);
	}

	/**
	 * Returns the delay cursor position.
	 *
	 * @return The current delay cursor value (always > cursor)
	 */
	public double getDelayCursor() { return getB(); }

	/**
	 * Creates a hardware-accelerated operation to increment both cursors.
	 *
	 * <p>This method generates a compilable operation that adds the specified value
	 * to both cursor positions. The value is repeated to apply to both components
	 * of the pair simultaneously.</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * CursorPair pair = new CursorPair(0.0, 1.0);
	 * Producer<PackedCollection> delta = c(p(new Pair(1.0, 0.0)));
	 *
	 * Supplier<Runnable> incrementOp = pair.increment(delta);
	 * incrementOp.get().run();  // Both cursors incremented
	 * }</pre>
	 *
	 * @param value Producer providing the increment value (repeated for both cursors)
	 * @return A compilable increment operation
	 */
	public Supplier<Runnable> increment(Producer<PackedCollection> value) {
		return a("CursorPair Increment", p(this), add(p(this), c(value).repeat(2)));
	}
}
