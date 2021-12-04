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

import io.almostrealism.code.CodePrintWriter;
import org.almostrealism.c.CJNIPrintWriter;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.c.NativeMemoryProvider;
import org.almostrealism.c.OpenCLPrintWriter;
import org.almostrealism.hardware.cl.CLJNIPrintWriter;
import org.almostrealism.io.PrintWriter;

import java.util.function.Function;

@Deprecated
public enum Compilation {
	C, CL, JNI;

	public Function<PrintWriter, CodePrintWriter> getGenerator() {
		throw new UnsupportedOperationException();
	}
}
