/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.algebra;

import java.util.LinkedList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.almostrealism.html.Div;
import io.almostrealism.html.HTMLContent;
import io.almostrealism.html.HTMLString;

/**
 * An arbitrary dimension tensor implemented as a recursive {@link LinkedList}.
 * 
 * @author  Michael Murray
 */
public class Tensor<T> implements HTMLContent {
	private final LinkedList top;
	
	public Tensor() { top = new LinkedList(); }
	
	public synchronized void insert(T o, int... loc) {
		LinkedList l = top;
		
		for (int i = 0; i < loc.length - 1; i++) {
			assert l != null;
			l = get(l, loc[i], true);
		}
		
		int newLocation = loc[loc.length - 1];

		assert l != null;
		if (l.size() <= newLocation) {
			for (int j = l.size(); j <= newLocation; j++) {
				l.add(new Leaf(null));
			}
		}
		
		l.set(newLocation, new Leaf(o));
	}
	
	public T get(int... loc) {
		LinkedList l = top;
		
		for (int i = 0; i < loc.length - 1; i++) {
			l = get(l, loc[i], false);
			if (l == null) return null;
		}
		
		Object o = l.size() <= loc[loc.length - 1] ? null : l.get(loc[loc.length - 1]);
		if (o instanceof LinkedList) return null;
		if (o == null) return null;
		return ((Leaf<T>) o).get();
	}

	public int length(int... loc) {
		LinkedList l = top;

		for (int j : loc) {
			l = get(l, j, false);
			if (l == null) return 0;
		}

		return l.size();
	}

	public void trim(int... max) {
		trim(top, max, 0);
	}

	private void trim(LinkedList l, int max[], int indexInMax) {
		while (l.size() > max[indexInMax]) l.removeLast();

		if (indexInMax < max.length - 1) {
			l.forEach(v -> trim((LinkedList) v, max, indexInMax + 1));
		}
	}
	
	@Override
	public String toHTML() {
		Div d = new Div();
		d.addStyleClass("tensor-table");
		
		i: for (int i = 0; ; i++) {
			if (get(i, 0) == null) break i;
			
			Div row = new Div();
			row.addStyleClass("tensor-row");
			
			j: for (int j = 0; ; j++) {
				T o = get(i, j);
				if (o == null) break j;
				
				if (o instanceof HTMLContent) {
					row.add((HTMLContent) o);
				} else if (o instanceof String) {
					Div cell = new Div();
					cell.addStyleClass("tensor-cell");
					cell.add(new HTMLString((String) o));
					row.add(cell);
				} else if (o instanceof Scalar) {
					Div cell = new Div();
					cell.addStyleClass("tensor-cell");
					cell.add(new HTMLString(String.valueOf(((Scalar) o).getValue())));
					row.add(cell);
				} else {
					Div cell = new Div();
					cell.addStyleClass("tensor-cell");
					cell.add(new HTMLString(o.getClass().getSimpleName()));
					row.add(cell);
				}
			}
			
			d.add(row);
		}
		
		return d.toHTML();
	}
	
	private static class Leaf<T> implements Future<T> {
		private final T o;

		public Leaf(T o) { this.o = o; }

		@Override public T get() { return o; }
		
		@Override
		public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
			return get();
		}
		
		@Override
		public boolean cancel(boolean mayInterruptIfRunning) { return false; }
		
		@Override
		public boolean isCancelled() { return false; }
		
		@Override
		public boolean isDone() { return true; }
	}
	
	private static LinkedList get(LinkedList l, int i, boolean create) {
		if (l.size() <= i) {
			if (create) {
				for (int j = l.size(); j <= i; j++) {
					l.add(new LinkedList());
				}
			} else {
				return null;
			}
		}
		
		Object o = l.get(i);
		
		if (o instanceof Leaf) {
			LinkedList newList = new LinkedList();
			newList.set(0, o);
			l.set(i, newList);
			return newList;
		}
		
		return (LinkedList) o;
	}
}
