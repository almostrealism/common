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

package io.almostrealism.c;

import io.almostrealism.code.CodePrintWriterAdapter;
import io.almostrealism.code.Method;
import io.almostrealism.code.ResourceVariable;
import io.almostrealism.code.Variable;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

public class CPrintWriter extends CodePrintWriterAdapter {
	public CPrintWriter(PrintWriter p) {
		super(p);
		setScopePrefix("void");
	}

	@Override
	public void println(Variable variable, boolean create) {
		if (create) {
			if (variable.getData() == null) {
				this.p.println(typeString(variable.getType()) + " " + variable.getName());
			} else {
				this.p.println(typeString(variable.getType()) + " " + variable.getName() +
								" = " + encode(variable.getData()) + ";");
			}
		} else {
			if (variable.getData() == null) {
				this.p.println(variable.getName() + " = null");
			} else {
				this.p.println(variable.getName() + " = " +
								encode(variable.getData()) + ";");
			}
		}
	}

	@Override
	public void println(Method method) {
		this.p.println(method.getName());
	}

	protected static String typeString(Class type) {
		if (type == null) return "";

		if (type == Vector.class) {
			return "vec3";
		} else if (type == Pair.class) {
			return "vec2";
		} else {
			throw new IllegalArgumentException("Unable to encode " + type);
		}
	}

	protected static String encode(Object data) {
		if (data instanceof Vector) {
			Vector v = (Vector) data;
			return "vec3(" + v.getX() + ", " + v.getY() + ", " + v.getZ() + ")";
		} else if (data instanceof Pair) {
			Pair v = (Pair) data;
			return "vec2(" + v.getX() + ", " + v.getY() + ")";
		} else {
			throw new IllegalArgumentException("Unable to encode " + data);
		}
	}

	protected static String toString(Map<String, Variable> args, List<String> argumentOrder) {
		StringBuffer buf = new StringBuffer();

		i: for (int i = 0; i < argumentOrder.size(); i++) {
			Variable v = args.get(argumentOrder.get(i));

			if (v instanceof ResourceVariable) {
				buf.append(encode(v.getData()));
			}

			if (i < (argumentOrder.size() - 1)) {
				buf.append(", ");
			}
		}

		return buf.toString();
	}
}
