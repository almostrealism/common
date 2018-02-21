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

package org.almostrealism.util;

import java.util.SortedSet;
import java.util.TreeSet;

public class PriorityQueue {
	private static double c = Math.pow(10.0, 10.0);
	private SortedSet data;
	
	protected class StoredItem implements Comparable {
		Object o;
		double p;
		
		public StoredItem(Object o, double p) {
			this.o = o;
			this.p = p;
		}
		
		public boolean equals(Object o) {
			if (o instanceof StoredItem == false) return false;
			if (((StoredItem)o).o != this.o) return false;
			if (((StoredItem)o).p != this.p) return false;
			return true;
		}
		
		public int hashCode() {
			return (int) (p * c);
		}
		
		public int compareTo(Object o) {
			if (o instanceof StoredItem == false) return Integer.MIN_VALUE;
			int x = (int) ((((StoredItem) o).p - this.p) * c);
			return x;
		}
	}
	
	public PriorityQueue() {
		this.data = new TreeSet();
	}
	
	public int put(Object o, double p) {
		this.data.add(new StoredItem(o, p));
		return this.data.size();
	}
	
	public double peek() {
		if (this.data.size() <= 0) return Double.MAX_VALUE;
		StoredItem s = (StoredItem) this.data.last();
		StoredItem f = (StoredItem) this.data.first();
		if (f.p - s.p < 0)
			System.out.println("PriorityQueue: Last - Next = " + (f.p - s.p));
		return s.p;
	}
	
	public Object peekNext() {
		if (this.data.size() <= 0) return null;
		StoredItem s = (StoredItem) this.data.last();
		
		return s.o;
	}
	
	public Object next() {
		StoredItem s = (StoredItem) this.data.last();
		this.data.remove(s);
		return s.o;
	}
	
	public int size() { return this.data.size(); }
}
