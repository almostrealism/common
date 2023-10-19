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

import io.almostrealism.lang.DefaultLanguageOperations;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Metric;
import io.almostrealism.scope.Scope;
import org.almostrealism.io.PrintWriter;

import java.util.List;
import java.util.Stack;

public abstract class CodePrintWriterAdapter implements CodePrintWriter {
	protected boolean enableWarnOnExplictParams = true;

	protected PrintWriter p;
	protected LanguageOperations language;

	private String nameSuffix = "";
	private String scopePrefixExt, scopePrefixInt;
	private String scopeSuffix = "{";
	private String scopeClose = "}";

	private final Stack<String> scopeName;

	public CodePrintWriterAdapter(PrintWriter p, LanguageOperations language) {
		this.p = p;
		this.language = language;
		this.scopeName = new Stack<>();
	}

	@Override
	public LanguageOperations getLanguage() {
		return language;
	}

	protected void setNameSuffix(String suffix) { this.nameSuffix = suffix; }

	protected void setScopePrefix(String prefix) {
		setExternalScopePrefix(prefix);
		setInternalScopePrefix(prefix);
	}

	protected void setExternalScopePrefix(String prefix) { this.scopePrefixExt = prefix; }
	protected void setInternalScopePrefix(String prefix) { this.scopePrefixInt = prefix; }

	protected void setScopeSuffix(String suffix) { this.scopeSuffix = suffix; }
	protected void setScopeClose(String close) { this.scopeClose = close; }

	protected String typePrefix(Class type) {
		if (type == null) {
			return "";
		} else {
			return language.nameForType(type) + " ";
		}
	}

	protected String getCurrentScopeName() {
		return scopeName.peek();
	}

	@Override
	public void println(Metric m) {
		m.getVariables().keySet().forEach(this::comment);
	}

	@Override
	public void comment(String text) {
		println("// " + text);
	}

	@Override
	@Deprecated
	public void println(String s) { p.println(s); }

	@Override
	public void println(Method<?> method) {
		p.println(language.renderMethod(method));
	}

	@Override
	public void println(Scope<?> s) {
		beginScope(s.getName(), null, s.getArgumentVariables(), Accessibility.EXTERNAL);
		s.write(this);
		endScope();
	}

	@Override
	public void flush() { }

	@Override
	public void beginScope(String name, OperationMetadata metadata, List<ArrayVariable<?>> arguments, Accessibility access) {
		scopeName.push(name);

		StringBuilder buf = new StringBuilder();

		String scopePrefix = access == Accessibility.EXTERNAL ? scopePrefixExt : scopePrefixInt;

		if (name != null) {
			if (scopePrefix != null) { buf.append(scopePrefix); buf.append(" "); }

			buf.append(name);

			if (nameSuffix != null) {
				buf.append(nameSuffix);
			}

			buf.append("(");
			((DefaultLanguageOperations) language).renderArguments(arguments, buf::append, access);
			buf.append(")");
		}

		if (scopeSuffix != null) { buf.append(" "); buf.append(scopeSuffix); }

		p.println(buf.toString());
	}

	@Override
	public void endScope() {
		scopeName.pop();
		p.println();
		if (scopeClose != null) p.println(scopeClose);
	}
}
