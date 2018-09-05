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

import java.util.List;
import java.util.Map;

/**
 * A {@link Method} is included in a {@link Scope} to indicate that a function should
 * be called in whatever language that the {@link Scope} is being exported to.
 */
public class Method implements Nameable {
	private String member, name;
	private List<String> argOrder;
	private Map<String, Variable> arguments;

	public Method(String name, List<String> argOrder, Map<String, Variable> arguments) {
		this(null, name, argOrder, arguments);
	}

	public Method(String member, String name, List<String> argOrder, Map<String, Variable> arguments) {
		this.member = member;
		this.name = name;
		this.argOrder = argOrder;
		this.arguments = arguments;
	}

	public void setName(String n) { this.name = n; }
	public String getName() { return name; }
	public void setMember(String m) { this.member = m; }
	public String getMember() { return this.member; }

	public Map<String, Variable> getArguments() { return arguments; }
	public List<String> getArgumentOrder() { return argOrder; }
}
