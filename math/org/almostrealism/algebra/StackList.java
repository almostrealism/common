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

import java.util.ArrayList;

/**
 * Stack-based object pool, see the example for usage. You must use the {@link #returning}
 * method for returning stack-allocated instance.<p>
 * 
 * Example code:
 * 
 * <pre>
 * StackList&lt;Vector3f&gt; vectors;
 * ...
 * 
 * vectors.push();
 * try {
 *     Vector3f vec = vectors.get();
 *     ...
 *     return vectors.returning(vec);
 * }
 * finally {
 *     vectors.pop();
 * }
 * </pre>
 * 
 * @author jezek2
 */
public abstract class StackList<T> {
	private final ArrayList<T> list = new ArrayList<>();
	private T returnObj;
	
	private int[] stack = new int[512];
	private int stackCount = 0;
	
	private int pos = 0;
	
	public StackList() {
		returnObj = create();
	}
	
	protected StackList(boolean unused) { }
	
	/** Pushes the stack. */
	public void push() {
		/*if (stackCount == stack.length-1) {
			resizeStack();
		}*/
		
		stack[stackCount++] = pos;
	}

	/** Pops the stack. */
	public void pop() {
		pos = stack[--stackCount];
	}
	
	/**
	 * Returns instance from stack pool, or create one if not present. The returned
	 * instance will be automatically reused when {@link #pop} is called.
	 * 
	 * @return instance
	 */
	public T get() {
		//if (true) return create();
		
		if (pos == list.size()) {
			expand();
		}
		
		return list.get(pos++);
	}
	
	/**
	 * Copies given instance into one slot static instance and returns it. It's
	 * essential that caller of method (that uses this method for returning instances)
	 * immediately copies it into own variable before any other usage.
	 * 
	 * @param obj stack-allocated instance
	 * @return one slot instance for returning purposes
	 */
	public T returning(T obj) {
		//if (true) { T ret = create(); copy(ret, obj); return ret; }
		
		copy(returnObj, obj);
		return returnObj;
	}
	
	/**
	 * Creates a new instance of type.
	 * 
	 * @return instance
	 */
	protected abstract T create();
	
	/**
	 * Copies data from one instance to another.
	 * 
	 * @param dest
	 * @param src
	 */
	protected abstract void copy(T dest, T src);

	private void expand() {
		list.add(create());
	}
}
