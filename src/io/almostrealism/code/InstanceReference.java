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

/**
 * {@link InstanceReference} is used to reference a previously declared
 * {@link Variable}. {@link CodePrintWriter} implementations should
 * encode the data as a {@link String}, but unlike a normal {@link String}
 * {@link Variable} the text does not appear in quotes.
 */
public class InstanceReference extends Variable<String> {
	public InstanceReference(Variable<?> v) {
		this(v.getName());
	}

	public InstanceReference(String varName) {
		super(varName, varName);
	}

	/**
	 * Side-effects value returned by {@link #getData()} to match.
	 */
	public void setName(String varName) {
		super.setName(varName);
		super.setData(varName);
	}

	/**
	 * Side-effects value returned by {@link #getName()} to match.
	 */
	public void setData(String varName) {
		super.setName(varName);
		super.setData(varName);
	}
}