/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.utils;

import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A simple priority queue backed by a {@link java.util.SortedSet}, used in spatial partitioning
 * algorithms to retrieve elements in order of ascending priority.
 *
 * @deprecated This class is superseded by {@link java.util.PriorityQueue} from the standard library.
 * @author Michael Murray
 */
@Deprecated
public class PriorityQueue {
	/** Scale factor used to convert double priorities to integer comparison values. */
	private static double c = Math.pow(10.0, 10.0);

	/** The sorted backing store for queued items. */
	private SortedSet data;

	/**
	 * A comparable wrapper that pairs an object with a double priority value.
	 *
	 * <p>Items with lower priority values sort last in the set (i.e., are retrieved first
	 * by {@link #next()}).</p>
	 */
	protected static class StoredItem implements Comparable<StoredItem> {
		/** The stored object. */
		Object o;

		/** The priority of the stored object (lower = higher priority). */
		double p;

		/**
		 * Constructs a {@link StoredItem} wrapping the given object with the specified priority.
		 *
		 * @param o the object to store
		 * @param p the priority value
		 */
		public StoredItem(Object o, double p) {
			this.o = o;
			this.p = p;
		}

		/**
		 * Returns {@code true} if {@code o} is a {@link StoredItem} with the same object and priority.
		 *
		 * @param o the object to compare
		 * @return {@code true} if equal
		 */
		@Override
		public boolean equals(Object o) {
			if (o instanceof StoredItem == false) return false;
			if (((StoredItem)o).o != this.o) return false;
			if (((StoredItem)o).p != this.p) return false;
			return true;
		}
		
		/**
		 * Returns a hash code based on the priority value.
		 *
		 * @return the hash code
		 */
		@Override
		public int hashCode() {
			return (int) (p * c);
		}

		/**
		 * Compares this item to another by priority, such that lower priorities sort last.
		 *
		 * @param o the object to compare to (must be a {@link StoredItem})
		 * @return a negative integer, zero, or a positive integer
		 */
		@Override
		public int compareTo(StoredItem o) {
			int x = (int) ((o.p - this.p) * c);
			return x;
		}
	}
	
	/**
	 * Constructs an empty {@link PriorityQueue}.
	 */
	public PriorityQueue() {
		this.data = new TreeSet();
	}

	/**
	 * Inserts an object with the given priority into the queue.
	 *
	 * @param o the object to enqueue
	 * @param p the priority (lower values are retrieved first)
	 * @return the new size of the queue
	 */
	public int put(Object o, double p) {
		this.data.add(new StoredItem(o, p));
		return this.data.size();
	}
	
	/**
	 * Returns the priority of the next item to be retrieved, or {@link Double#MAX_VALUE} if empty.
	 *
	 * @return the lowest priority value currently in the queue
	 */
	public double peek() {
		if (this.data.size() <= 0) return Double.MAX_VALUE;
		StoredItem s = (StoredItem) this.data.last();
		StoredItem f = (StoredItem) this.data.first();
		if (f.p - s.p < 0)
			System.out.println("PriorityQueue: Last - Next = " + (f.p - s.p));
		return s.p;
	}
	
	/**
	 * Returns the object at the front of the queue without removing it.
	 *
	 * @return the highest-priority object, or {@code null} if the queue is empty
	 */
	public Object peekNext() {
		if (this.data.size() <= 0) return null;
		StoredItem s = (StoredItem) this.data.last();
		
		return s.o;
	}
	
	/**
	 * Removes and returns the highest-priority object from the queue.
	 *
	 * @return the highest-priority object
	 */
	public Object next() {
		StoredItem s = (StoredItem) this.data.last();
		this.data.remove(s);
		return s.o;
	}
	
	/**
	 * Returns the number of items currently in the queue.
	 *
	 * @return the queue size
	 */
	public int size() { return this.data.size(); }
}
