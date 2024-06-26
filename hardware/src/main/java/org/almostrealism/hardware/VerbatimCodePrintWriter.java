/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.lang.CodePrintWriterAdapter;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.scope.Method;
import org.almostrealism.io.PrintWriter;

public class VerbatimCodePrintWriter extends CodePrintWriterAdapter {
	public VerbatimCodePrintWriter(PrintWriter pw) {
		super(pw, null);
	}

	@Override
	public void println(ExpressionAssignment<?> v) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void println(Method<?> m) {
		throw new UnsupportedOperationException();
	}
}
