/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.econ;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class ExpenseRange {
	private Time start, end;
	private ExpenseData data;

	private ExpenseRange before;
	// TODO  Add ExpenseRange after (?)


	// TODO  Change these to Currency types
	private double open, close;
	private double high, low;

	private TreeSet<Time> sortedTimes;

	public ExpenseRange() {
		this.data = new ExpenseData();
		this.sortedTimes = new TreeSet<>();
	}

	/**
	 * Creates an {@link ExpenseRange} for expenses between the start time (inclusive),
	 * and the end time (exclusive).
	 */
	public ExpenseRange(Time start, Time end) {
		this();
		this.start = start;
		this.end = end;
	}

	/**
	 * If the specified {@link Time} is within the range for this {@link ExpenseRange},
	 * the specified {@link Expense} is added. If the {@link ExpenseRange} has a known
	 * starting {@link Time} and the specified {@link Time} is before it, this method
	 * will attempt to add to the prior {@link ExpenseRange}. If neither of these
	 * conditions is met, false is returned.
	 *
	 * @see  #before()
	 */
	public boolean add(Time t, Expense e) {
		if (this.start != null && t.isBefore(start, false)) {
			return this.before().add(t, e);
		} else if (this.end != null && t.isAfter(end, true)) {
			return false;
		}

		boolean empty = data.isEmpty();

		Expense prev = this.data.put(t, e);
		if (prev != null) {
			System.out.println("Warning: " + prev + " was replaced in " + this);
			System.out.println("Warning: Replacing expense data may damage the accuracy of OHLC metrics");
		}

		sortedTimes.add(t);

		FloatingPointUnit c = (FloatingPointUnit) e.getCost();
		double v = c.asDouble();
		if (empty || v > high) this.high = v;
		if (empty || v < low) this.low = v;
		if (empty || t.equals(getEarliest())) this.open = v;
		if (empty || t.equals(getLatest())) this.close = v;

		return true;
	}

	public boolean add(Map.Entry<Time, Expense> ent) {
		return add(ent.getKey(), ent.getValue());
	}

	public boolean addAll(Map<Time, Expense> m) {
		boolean added = true;

		for (Map.Entry<Time, Expense> e : m.entrySet()) {
			added = add(e) & added;
		}

		return added;
	}

	protected TreeSet<Time> sortedTimes() { return sortedTimes; }

	public Time getEarliest() { return sortedTimes.isEmpty() ? null : sortedTimes().first(); }
	public Time getLatest() { return sortedTimes.isEmpty() ? null : sortedTimes().last(); }

	public Time getStart() { return start; }
	public Time getEnd() { return end; }

	public Time getDuration() { return getEnd().subtract(getStart()); }

	public double getOpen() { return open; }
	public double getHigh() { return high; }
	public double getLow() { return low; }
	public double getClose() { return close; }

	public List<ExpenseRange> flatten() {
		if (before == null) {
			ArrayList<ExpenseRange> l = new ArrayList<>();
			l.add(this);
			return l;
		} else {
			List<ExpenseRange> l = before.flatten();
			l.add(this);
			return l;
		}
	}

	public ExpenseRange before() {
		if (before == null) {
			if (start == null) {
				before = null;
			} else if (end == null) {
				before = new ExpenseRange(null, getStart());
			} else {
				Time d = getDuration();
				before = new ExpenseRange(getStart().subtract(d), getEnd().subtract(d));
			}
		}

		return before;
	}

	public String toString() {
		return "ExpenseRange[" + getDuration().inMinutes() +
			" minutes, " + data.size() + " entries]";
	}
}
