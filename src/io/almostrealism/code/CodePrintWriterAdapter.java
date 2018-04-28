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

import org.almostrealism.relation.Computation;

import java.io.PrintWriter;

public abstract class CodePrintWriterAdapter implements CodePrintWriter {
	protected PrintWriter p;

	private String nameSuffix = "()";
	private String scopePrefix;
	private String scopeSuffix = "{";
	private String scopeClose = "}";

	public CodePrintWriterAdapter(PrintWriter p) { this.p = p; }

	protected void setNameSuffix(String suffix) { this.nameSuffix = suffix; }
	protected void setScopePrefix(String prefix) { this.scopePrefix = prefix; }
	protected void setScopeSuffix(String suffix) { this.scopeSuffix = suffix; }
	protected void setScopeClose(String close) { this.scopeClose = close; }

	public void println(Computation c) {
		c.getScope("compute").write(this);
	}

	public void flush() { p.flush(); }

	@Override
	public void beginScope(String name) {
		StringBuffer buf = new StringBuffer();

		if (name != null) {
			if (scopePrefix != null) { buf.append(scopePrefix); buf.append(" "); }

			buf.append(name);

			if (nameSuffix != null) {
				buf.append(nameSuffix);
			}
		}

		if (scopeSuffix != null) { buf.append(" "); buf.append(scopeSuffix); }

		p.println(buf.toString());
	}

	@Override
	public void endScope() {
		p.println();
		if (scopeClose != null) p.println(scopeClose);
	}
}
