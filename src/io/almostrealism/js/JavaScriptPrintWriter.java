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

package io.almostrealism.js;

import io.almostrealism.code.CodePrintWriter;
import io.almostrealism.code.Method;
import io.almostrealism.code.ResourceVariable;
import io.almostrealism.code.Variable;
import org.almostrealism.io.Resource;
import org.almostrealism.io.ResourceTranscoder;
import org.almostrealism.io.ResourceTranscoderFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A {@link CodePrintWriter} implementation for writing JavaScript.
 *
 * @author  Michael Murray
 */
public class JavaScriptPrintWriter implements CodePrintWriter {
	private PrintWriter p;

	public JavaScriptPrintWriter(PrintWriter p) {
		this.p = p;
	}

	@Override
	public void println(Variable v) {
		if (v instanceof ResourceVariable) {
			p.println("var " + v.getName() + " = " + toJson((ResourceVariable) v));
		} else {
			p.println("var " + v.getName() + " = " + v.getData() + ";");
		}
	}

	@Override
	public void println(Method m) {
		if (m.getMember() == null) {
			p.println(m.getName() + "(" + toString(m.getArguments(), m.getArgumentOrder()) + ");");
		} else {
			p.println(m.getMember() + "." + m.getName() + "(" + toString(m.getArguments(), m.getArgumentOrder()) + ");");
		}
	}

	@Override
	public void beginScope(String name) {
		if (name == null) {
			p.println("{");
		} else {
			p.println("function " + name + "() {");
		}
	}

	@Override
	public void endScope() { p.println("}"); }

	protected static String toJson(ResourceVariable v) {
		JsonResource json;
		Resource r = v.getResource();
		ResourceTranscoderFactory f = new ResourceTranscoderFactory(r.getClass(), JsonResource.class);

		try {
			ResourceTranscoder t = f.construct();
			System.out.println("JavaScriptPrintWriter: Transcoder for " + v + " is " + t);
			json = (JsonResource) t.transcode(r);
			return new String(json.getData());
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	protected static String toJson(Object o) {
		if (o instanceof String) {
			return "\"" + o + "\"";
		} else if (o instanceof String[]) {
			return Arrays.toString((String[]) o);
		} else if (o instanceof Boolean || o instanceof Number) {
			return String.valueOf(o);
		} else if (o instanceof Number[]) {
			return Arrays.toString((Number[]) o);
		} else if (o instanceof boolean[]) {
			return Arrays.toString((boolean[]) o);
		} else if (o instanceof int[]) {
			return Arrays.toString((int[]) o);
		} else if (o instanceof float[]) {
			return Arrays.toString((float[]) o);
		} else if (o instanceof double[]) {
			return Arrays.toString((double[]) o);
		} else {
			throw new IllegalArgumentException("Unable to encode type " + o.getClass().getName());
		}
	}

	protected static String toString(Map<String, Variable> args, List<String> argumentOrder) {
		StringBuffer buf = new StringBuffer();

		i: for (int i = 0; i < argumentOrder.size(); i++) {
			Variable v = args.get(argumentOrder.get(i));

			if (v instanceof ResourceVariable) {
				buf.append(toJson((ResourceVariable) v));
			} else {
				buf.append(toJson(v.getData()));
			}

			if (i < (argumentOrder.size() - 1)) {
				buf.append(", ");
			}
		}

		return buf.toString();
	}
}
