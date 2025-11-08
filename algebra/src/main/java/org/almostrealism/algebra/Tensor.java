/*
 * Copyright 2023 Michael Murray
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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import io.almostrealism.html.Div;
import io.almostrealism.html.HTMLContent;
import io.almostrealism.html.HTMLString;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryData;

/**
 * An arbitrary-dimension tensor implemented as a recursive {@link LinkedList} structure.
 *
 * <p>
 * {@link Tensor} provides a flexible, dynamically-growable multi-dimensional array structure
 * that can store any type of object. Unlike {@link PackedCollection}-based types which have
 * fixed dimensions and store primitive values, Tensor uses nested LinkedLists to support
 * arbitrary dimensions and generic object storage.
 * </p>
 *
 * <h2>Structure</h2>
 * <p>
 * The tensor is implemented as a tree of {@link LinkedList}s:
 * </p>
 * <ul>
 *   <li>Leaf nodes contain {@link Leaf} wrappers around actual values</li>
 *   <li>Non-leaf nodes contain {@link LinkedList}s representing lower dimensions</li>
 *   <li>Dimensions grow dynamically as needed when elements are inserted</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Creating and Populating a Tensor</h3>
 * <pre>{@code
 * // Create a 2D tensor (matrix)
 * Tensor<Double> matrix = new Tensor<>();
 * matrix.insert(1.0, 0, 0);
 * matrix.insert(2.0, 0, 1);
 * matrix.insert(3.0, 1, 0);
 * matrix.insert(4.0, 1, 1);
 *
 * // Access values
 * Double value = matrix.get(0, 1);  // 2.0
 * }</pre>
 *
 * <h3>Working with Higher Dimensions</h3>
 * <pre>{@code
 * // Create a 3D tensor
 * Tensor<String> tensor3D = new Tensor<>();
 * tensor3D.insert("value", 0, 0, 0);
 * tensor3D.insert("another", 1, 2, 3);
 *
 * // Query dimensions
 * int dims = tensor3D.getDimensions();  // 3
 * int size = tensor3D.getTotalSize();   // 2
 * }</pre>
 *
 * <h3>Converting to PackedCollection</h3>
 * <pre>{@code
 * // Create tensor with numeric values
 * Tensor<Double> t = new Tensor<>();
 * t.insert(1.0, 0, 0);
 * t.insert(2.0, 0, 1);
 * t.insert(3.0, 1, 0);
 * t.insert(4.0, 1, 1);
 *
 * // Pack into a PackedCollection for hardware acceleration
 * PackedCollection<?> packed = t.pack();
 * }</pre>
 *
 * <h3>HTML and CSV Export</h3>
 * <pre>{@code
 * Tensor<Scalar> data = new Tensor<>();
 * // ... populate data ...
 *
 * String html = data.toHTML();  // Generate HTML table
 * String csv = data.toCSV();    // Generate CSV format
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * The {@link #insert(Object, int...)} method is synchronized. However, for concurrent
 * access, external synchronization may be required depending on usage patterns.
 * </p>
 *
 * @param <T>  the type of elements stored in the tensor
 * @author  Michael Murray
 * @see PackedCollection
 * @see TraversalPolicy
 */
public class Tensor<T> implements HTMLContent {
	private final LinkedList top;

	/**
	 * Constructs a new empty {@link Tensor}.
	 */
	public Tensor() { top = new LinkedList(); }

	/**
	 * Inserts an element at the specified multi-dimensional location.
	 * The tensor will automatically grow to accommodate the specified location.
	 * This method is synchronized for thread safety.
	 *
	 * @param o    the element to insert
	 * @param loc  the multi-dimensional index where the element should be inserted
	 */
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

	/**
	 * Retrieves the element at the specified multi-dimensional location.
	 *
	 * @param loc  the multi-dimensional index of the element to retrieve
	 * @return the element at the specified location, or null if no element exists there
	 */
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

	/**
	 * Returns the length of the tensor at the specified multi-dimensional location.
	 * This gives the size of the list at that dimension.
	 *
	 * @param loc  the multi-dimensional index to query
	 * @return the length at the specified location, or 0 if the location doesn't exist
	 */
	public int length(int... loc) {
		LinkedList l = top;

		for (int j : loc) {
			l = get(l, j, false);
			if (l == null) return 0;
		}

		return l.size();
	}

	/**
	 * Returns the number of dimensions in this {@link Tensor}.
	 * Walks through the tensor structure to determine the maximum depth.
	 *
	 * @return the number of dimensions
	 */
	public int getDimensions() {
		int dims = 0;

		w: while (true) {
			int length = length(new int[dims]);
			if (length <= 0) break w;
			dims++;
		}

		return dims;
	}

	/**
	 * Returns the total number of leaf elements in this {@link Tensor}.
	 * This counts all non-null elements across all dimensions.
	 *
	 * @return the total number of elements
	 */
	public int getTotalSize() {
		return totalSize(top);
	}

	/**
	 * Recursively counts the total number of leaf elements in a list structure.
	 *
	 * @param l  the list to count elements in
	 * @return the total number of leaf elements
	 */
	protected int totalSize(List<T> l) {
		int size = 0;

		for (T o : l) {
			if (o instanceof LinkedList) {
				size += totalSize((LinkedList) o);
			} else {
				size++;
			}
		}

		return size;
	}

	/**
	 * Packs this {@link Tensor} into a {@link PackedCollection} for hardware-accelerated operations.
	 *
	 * <p>
	 * This method converts the flexible tensor structure into a fixed-shape PackedCollection.
	 * If the tensor contains {@link MemoryData} objects, they are packed with their memory
	 * layout preserved. Otherwise, numeric values are extracted and packed into a collection.
	 * </p>
	 *
	 * @return a {@link PackedCollection} containing the tensor data
	 * @throws ClassCastException if tensor contains non-numeric, non-MemoryData values
	 */
	public PackedCollection<?> pack() {
		TraversalPolicy shape = new TraversalPolicy(IntStream.range(0, getDimensions()).mapToObj(i -> new int[i]).mapToInt(this::length).toArray());
		if (get(new int[shape.getDimensions()]) instanceof MemoryData) {
			TraversalPolicy targetShape = shape.appendDimension(((MemoryData) get(new int[shape.getDimensions()])).getMemLength());
			return packMem(shape, targetShape);
		}

		PackedCollection<?> c = new PackedCollection<>(shape);

		AtomicInteger index = new AtomicInteger();
		shape.stream().forEach(pos -> c.setMem(index.getAndIncrement(), ((Number) get(pos)).doubleValue()));
		return c;
	}

	/**
	 * Packs a tensor containing {@link MemoryData} elements into a {@link PackedCollection}.
	 *
	 * @param shape        the shape of the tensor structure
	 * @param targetShape  the target shape including memory data dimensions
	 * @return a packed collection containing all memory data
	 */
	private PackedCollection<?> packMem(TraversalPolicy shape, TraversalPolicy targetShape) {
		PackedCollection<?> c = new PackedCollection<>(targetShape);

		AtomicInteger index = new AtomicInteger();
		shape.stream().forEach(pos -> {
			MemoryData d = (MemoryData) get(pos);
			c.setMem(index.getAndIncrement() * d.getMemLength(), d, 0, d.getMemLength());
		});

		return c;
	}

	/**
	 * Trims this {@link Tensor} to the specified maximum sizes for each dimension.
	 * Removes elements beyond the specified maximum indices.
	 *
	 * @param max  the maximum size for each dimension (must match the number of dimensions)
	 */
	public void trim(int... max) {
		trim(top, max, 0);
	}

	/**
	 * Recursively trims a list structure to the specified maximum sizes.
	 *
	 * @param l            the list to trim
	 * @param max          the maximum sizes for each dimension
	 * @param indexInMax   the current dimension being processed
	 */
	private void trim(LinkedList l, int max[], int indexInMax) {
		while (l.size() > max[indexInMax]) l.removeLast();

		if (indexInMax < max.length - 1) {
			l.forEach(v -> trim((LinkedList) v, max, indexInMax + 1));
		}
	}

	/**
	 * Converts this {@link Tensor} to an HTML table representation.
	 * Generates styled div elements for 2D tensor data.
	 *
	 * <p>
	 * The generated HTML includes:
	 * </p>
	 * <ul>
	 *   <li>tensor-table: Container div for the entire table</li>
	 *   <li>tensor-row: Div for each row</li>
	 *   <li>tensor-cell: Div for each cell value</li>
	 * </ul>
	 *
	 * <p>
	 * Special handling for:
	 * </p>
	 * <ul>
	 *   <li>{@link HTMLContent} objects - rendered via their toHTML() method</li>
	 *   <li>{@link Scalar} objects - displays the scalar value</li>
	 *   <li>{@link String} objects - displayed as-is</li>
	 *   <li>Other objects - displays the class simple name</li>
	 * </ul>
	 *
	 * @return an HTML string representation of this tensor
	 */
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

	/**
	 * Converts this {@link Tensor} to a CSV (Comma-Separated Values) representation.
	 * Generates a string with rows and columns separated by commas and newlines.
	 *
	 * <p>
	 * Value handling:
	 * </p>
	 * <ul>
	 *   <li>{@link Scalar} objects - exports the scalar value</li>
	 *   <li>{@link Number} objects - exports the numeric value</li>
	 *   <li>{@link String} objects - exported as-is</li>
	 *   <li>Other objects - exports the class simple name</li>
	 * </ul>
	 *
	 * @return a CSV string representation of this tensor
	 */
	public String toCSV() {
		StringBuilder buf = new StringBuilder();

		i: for (int i = 0; ; i++) {
			if (get(i, 0) == null) break i;

			j: for (int j = 0; ; j++) {
				T o = get(i, j);
				if (o == null) break j;
				if (j > 0) buf.append(",");

				if (o instanceof String) {
					buf.append((String) o);
				} else if (o instanceof Scalar) {
					buf.append(((Scalar) o).getValue());
				} else if (o instanceof Number) {
					buf.append(o);
				} else {
					buf.append(o.getClass().getSimpleName());
				}
			}

			buf.append("\n");
		}

		return buf.toString();
	}

	/**
	 * Returns a string representation of this {@link Tensor}.
	 * Delegates to the underlying LinkedList structure's toString method.
	 *
	 * @return a string representation of the tensor structure
	 */
	@Override
	public String toString() {
		return top.toString();
	}

	/**
	 * A leaf node in the tensor tree structure.
	 * Wraps an actual value and implements {@link Future} for compatibility with
	 * asynchronous computation patterns.
	 *
	 * @param <T>  the type of value stored in this leaf
	 */
	private static class Leaf<T> implements Future<T> {
		private final T o;

		/**
		 * Creates a new leaf node wrapping the specified value.
		 *
		 * @param o  the value to wrap
		 */
		public Leaf(T o) { this.o = o; }

		/**
		 * Returns the wrapped value.
		 *
		 * @return the value stored in this leaf
		 */
		@Override public T get() { return o; }

		/**
		 * Returns the wrapped value (ignores timeout parameters).
		 *
		 * @param timeout  ignored
		 * @param unit     ignored
		 * @return the value stored in this leaf
		 */
		@Override
		public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
			return get();
		}

		/**
		 * Always returns false as leaf values cannot be cancelled.
		 *
		 * @param mayInterruptIfRunning  ignored
		 * @return false
		 */
		@Override
		public boolean cancel(boolean mayInterruptIfRunning) { return false; }

		/**
		 * Always returns false as leaf values are never cancelled.
		 *
		 * @return false
		 */
		@Override
		public boolean isCancelled() { return false; }

		/**
		 * Always returns true as leaf values are immediately available.
		 *
		 * @return true
		 */
		@Override
		public boolean isDone() { return true; }

		/**
		 * Returns a string representation of the wrapped value.
		 *
		 * @return string representation of the value
		 */
		@Override
		public String toString() {
			return o.toString();
		}
	}

	/**
	 * Retrieves a LinkedList at the specified index within a parent list.
	 * Can optionally create missing intermediate lists.
	 *
	 * @param l       the parent list
	 * @param i       the index to retrieve
	 * @param create  if true, creates missing lists; if false, returns null for missing entries
	 * @return the LinkedList at index i, or null if it doesn't exist and create is false
	 */
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
//			LinkedList newList = new LinkedList();
//			newList.set(0, o);
//			l.set(i, newList);
//			return newList;
			return null;
		}
		
		return (LinkedList) o;
	}
}
