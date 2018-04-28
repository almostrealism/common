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

package io.almostrealism.code;

import org.almostrealism.util.Nameable;

/**
 * A {@link Variable} wraps some data that can be included in a {@link Scope}.
 *
 * @param <T>  Type of the underlying data.
 */
public class Variable<T> implements Nameable {
	private String name, annotation;
	private Class<T> type;
	private T data;

	public Variable(String name, T data) {
		this(name, (Class<T>) data.getClass(), data);
	}

	public Variable(String name, Class<T> type, T data) {
		setName(name);
		setType(type);
		this.data = data;
	}

	public void setName(String n) { this.name = n; }
	public String getName() { return this.name; }

	public void setType(Class<T> t) { this.type = t; }
	public Class<T> getType() { return this.type; }

	public void setAnnotation(String a) { this.annotation = a; }
	public String getAnnotation() { return this.annotation; }

	public void setData(T data) { this.data = data; }
	public T getData() { return data; }
}
