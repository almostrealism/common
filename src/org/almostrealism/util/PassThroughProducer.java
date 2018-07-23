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

public class PassThroughProducer<T> implements Producer<T> {
	private int argIndex = -1;

	public PassThroughProducer(int argIndex) {
		this.argIndex = argIndex;
	}

	/** Returns the argument at the index specified to the constructor of {@link PassThroughProducer}. */
	@Override
	public T evaluate(Object[] args) {
		return (T) args[argIndex];
	}

	/** Does nothing. */
	@Override
	public void compact() { }
}
