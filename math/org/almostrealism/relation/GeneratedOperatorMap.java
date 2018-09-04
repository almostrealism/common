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

package org.almostrealism.relation;

import io.almostrealism.c.CPrintWriter;
import io.almostrealism.code.CodePrintWriter;
import org.almostrealism.math.Hardware;
import org.almostrealism.math.HardwareOperatorMap;
import org.almostrealism.math.MemWrapper;

import java.io.PrintWriter;
import java.io.StringWriter;

public class GeneratedOperatorMap<T extends MemWrapper> extends HardwareOperatorMap<T> {
	public GeneratedOperatorMap(Hardware h, Computation<?> c) {
		StringWriter w = new StringWriter();
		CodePrintWriter p = new CPrintWriter(new PrintWriter(w));
		p.println(c);
		p.flush();

		System.out.println(w.getBuffer().toString());

		init(h, w.getBuffer().toString());
	}
}
