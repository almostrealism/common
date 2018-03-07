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

package org.almostrealism.algebra;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Object pool for arrays.
 * 
 * @author jezek2
 */
public class ArrayPool<T> {
	private Class componentType;
	private ArrayList list = new ArrayList();
	private Comparator comparator;
	private IntValue key = new IntValue();
	
	/**
	 * Creates object pool.
	 * 
	 * @param componentType
	 */
	public ArrayPool(Class componentType) {
		this.componentType = componentType;
		
		if (componentType == float.class) {
			comparator = floatComparator;
		}
		else if (!componentType.isPrimitive()) {
			comparator = objectComparator;
		}
		else {
			throw new UnsupportedOperationException("unsupported type "+componentType);
		}
	}
	
	@SuppressWarnings("unchecked")
	private T create(int length) {
		return (T)Array.newInstance(componentType, length);
	}
	
	/**
	 * Returns array of exactly the same length as demanded, or create one if not
	 * present in the pool.
	 * 
	 * @param length
	 * @return array
	 */
	@SuppressWarnings("unchecked")
	public T getFixed(int length) {
		key.value = length;
		int index = Collections.binarySearch(list, key, comparator);
		if (index < 0) {
			return create(length);
		}
		return (T)list.remove(index);
	}

	/**
	 * Returns array that has same or greater length, or create one if not present
	 * in the pool.
	 * 
	 * @param length the minimum length required
	 * @return array
	 */
	@SuppressWarnings("unchecked")
	public T getAtLeast(int length) {
		key.value = length;
		int index = Collections.binarySearch(list, key, comparator);
		if (index < 0) {
			index = -index - 1;
			if (index < list.size()) {
				return (T)list.remove(index);
			}
			else {
				return create(length);
			}
		}
		return (T)list.remove(index);
	}
	
	/**
	 * Releases array into object pool.
	 * 
	 * @param array previously obtained array from this pool
	 */
	@SuppressWarnings("unchecked")
	public void release(T array) {
		int index = Collections.binarySearch(list, array, comparator);
		if (index < 0) index = -index - 1;
		list.add(index, array);
		
		// remove references from object arrays:
		if (comparator == objectComparator) {
			Object[] objArray = (Object[])array;
			for (int i=0; i<objArray.length; i++) {
				objArray[i] = null;
			}
		}
	}

	private static Comparator floatComparator = new Comparator() {
		public int compare(Object o1, Object o2) {
			int len1 = (o1 instanceof IntValue)? ((IntValue)o1).value : ((float[])o1).length;
			int len2 = (o2 instanceof IntValue)? ((IntValue)o2).value : ((float[])o2).length;
			return len1 > len2? 1 : len1 < len2 ? -1 : 0;
		}
	};
	
	private static Comparator objectComparator = new Comparator() {
		public int compare(Object o1, Object o2) {
			int len1 = (o1 instanceof IntValue)? ((IntValue)o1).value : ((Object[])o1).length;
			int len2 = (o2 instanceof IntValue)? ((IntValue)o2).value : ((Object[])o2).length;
			return len1 > len2? 1 : len1 < len2 ? -1 : 0;
		}
	};
	
	private static class IntValue { public int value; }
}
