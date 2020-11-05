/*
 * Copyright 2020 Michael Murray
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

import org.almostrealism.io.PrintWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public abstract class CodePrintWriterAdapter implements CodePrintWriter {
	protected PrintWriter p;

	private String nameSuffix = "";
	private String scopePrefix;
	private String scopeSuffix = "{";
	private String scopeClose = "}";

	public CodePrintWriterAdapter(PrintWriter p) { this.p = p; }

	protected void setNameSuffix(String suffix) { this.nameSuffix = suffix; }
	protected void setScopePrefix(String prefix) { this.scopePrefix = prefix; }
	protected void setScopeSuffix(String suffix) { this.scopeSuffix = suffix; }
	protected void setScopeClose(String close) { this.scopeClose = close; }

	protected abstract String nameForType(Class<?> type);

	@Override
	public void println(Scope s) {
		beginScope(s.getName(), new ArrayList<>());
		s.write(this);
		endScope();
	}

	@Override
	public void flush() { }

	@Override
	public void beginScope(String name, List<Argument<?>> arguments) {
		StringBuffer buf = new StringBuffer();

		if (name != null) {
			if (scopePrefix != null) { buf.append(scopePrefix); buf.append(" "); }

			buf.append(name);

			if (nameSuffix != null) {
				buf.append(nameSuffix);
			}

			buf.append("(");
			renderArguments(arguments, buf::append);
			buf.append(")");
		}

		if (scopeSuffix != null) { buf.append(" "); buf.append(scopeSuffix); }

		p.println(buf.toString());
	}

	protected void renderArguments(List<Argument<?>> arguments, Consumer<String> out) {
		for (int i = 0; i < arguments.size(); i++) {
			if (arguments.get(i).getAnnotation() != null) {
				out.accept(arguments.get(i).getAnnotation());
				out.accept(" ");
			}

			out.accept(nameForType(arguments.get(i).getType()));
			out.accept(" ");
			out.accept(arguments.get(i).getName());

			if (i < arguments.size() - 1) {
				out.accept(", ");
			}
		}
	}

	@Override
	public void endScope() {
		p.println();
		if (scopeClose != null) p.println(scopeClose);
	}
}
