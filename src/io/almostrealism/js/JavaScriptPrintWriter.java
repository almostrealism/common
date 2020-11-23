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

import io.almostrealism.code.*;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.io.PrintWriter;
import org.almostrealism.io.Resource;
import org.almostrealism.io.ResourceTranscoder;
import org.almostrealism.io.ResourceTranscoderFactory;
import org.almostrealism.relation.Evaluable;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * A {@link CodePrintWriter} implementation for writing JavaScript.
 *
 * @author  Michael Murray
 */
public class JavaScriptPrintWriter extends CodePrintWriterAdapter {
	public JavaScriptPrintWriter(PrintWriter p) {
		super(p);
	}

	@Override
	public void println(Variable v) {
		if (v.isDeclaration()) {
			p.println("var " + v.getName() + " = " + toString(v) + ";");
		} else {
			p.println(v.getName() + " = " + toString(v) + ";");
		}
	}

	@Override
	public void println(Method m) {
		if (m.getMember() == null) {
			p.println(m.getName() + "(" + toString(m.getArguments()) + ");");
		} else {
			p.println(m.getMember() + "." + m.getName() + "(" + toString(m.getArguments()) + ");");
		}
	}

	@Override
	public void beginScope(String name, List<Argument<?>> arguments) {
		if (name == null) {
			p.println("{");
		} else {
			// TODO  Include arguments
			p.println("function " + name + "() {");
		}
	}

	@Override
	public void endScope() { p.println("}"); }

	@Override
	protected String nameForType(Class<?> type) { return ""; }

	protected static String toString(Variable v) {
		if (v instanceof ResourceVariable) {
			return toJson((ResourceVariable) v);
		} else if (v.getGenerator() != null) {
			Method m = v.getGenerator();
			
			StringBuffer b = new StringBuffer();
			if (m.getMember() != null)
				b.append(m.getMember() + ".");
			
			b.append(m.getName());
			b.append("(");
			b.append(toString(m.getArguments()));
			b.append(")");
			
			return b.toString();
		} else {
			return v.getProducer().toString();
		}
	}
	
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
		if (o instanceof Evaluable) {
			o = ((Evaluable) o).evaluate();
		}

		if (o instanceof Scalar) {
			o = ((Scalar) o).getValue();
		}

		if (o instanceof String) {
			return "\"" + o + "\"";
		} else if (o instanceof Boolean[]) {
			return Arrays.toString((Boolean[]) o);
		} else if (o instanceof String[]) {
			return Arrays.toString((String[]) o);
		} else if (o instanceof Boolean || o instanceof Number) {
			return String.valueOf(o);
		} else if (o instanceof Number[]) {
			return Arrays.toString((Number[]) o);
		} else if (o instanceof boolean[]) {
			return Arrays.toString((boolean[]) o);
		} else if (o instanceof byte[]) {
			return Arrays.toString((byte[]) o);
		} else if (o instanceof int[]) {
			return Arrays.toString((int[]) o);
		} else if (o instanceof float[]) {
			return Arrays.toString((float[]) o);
		} else if (o instanceof double[]) {
			return Arrays.toString((double[]) o);
		} else if (o != null && o.getClass().isArray()) {
			throw new IllegalArgumentException("Unable to encode array of type " + o.getClass().getTypeName());
		} else {
			throw new IllegalArgumentException("Unable to encode type " + o.getClass().getName());
		}
	}

	protected static String toString(List<Variable> arguments) {
		StringBuffer buf = new StringBuffer();

		for (int i = 0; i < arguments.size(); i++) {
			Variable<?> v = arguments.get(i);

			if (v instanceof ResourceArgument) {
				buf.append(toJson(v));
			} else if (v.getGenerator() != null) {
				buf.append(toString(v));
			} else if (v.getExpression() instanceof InstanceReference) {
				buf.append(v.getProducer().get().evaluate()); // TODO  This cant be right...
			} else {
				buf.append(toJson(v.getProducer()));
			}

			if (i < (arguments.size() - 1)) {
				buf.append(", ");
			}
		}

		return buf.toString();
	}
}
