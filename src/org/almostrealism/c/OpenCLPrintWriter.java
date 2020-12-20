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

package org.almostrealism.c;

import io.almostrealism.code.ArrayVariable;
import org.almostrealism.io.PrintWriter;

import java.util.List;
import java.util.function.Consumer;

public class OpenCLPrintWriter extends CPrintWriter {

	public OpenCLPrintWriter(PrintWriter p) {
		super(p);
		setScopePrefix("__kernel void");
	}

	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out) {
		if (!arguments.isEmpty()) {
			renderArguments(arguments, out, true, null, "*", "");
			out.accept(", ");
			renderArguments(arguments, out, false, Integer.class, "", "Offset");
			out.accept(", ");
			renderArguments(arguments, out, false, Integer.class, "", "Size");
		}
	}

	private void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, boolean enableAnnotation, Class replaceType, String prefix, String suffix) {
		for (int i = 0; i < arguments.size(); i++) {
			if (enableAnnotation && arguments.get(i).getAnnotation() != null) {
				out.accept(arguments.get(i).getAnnotation());
				out.accept(" ");
			}

			out.accept(nameForType(replaceType == null ? arguments.get(i).getType() : replaceType));
			out.accept(" ");
			out.accept(prefix);
			out.accept(arguments.get(i).getName());
			out.accept(suffix);

			if (i < arguments.size() - 1) {
				out.accept(", ");
			}
		}
	}
}
