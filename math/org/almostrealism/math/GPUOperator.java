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

package org.almostrealism.math;

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.relation.Operator;

import org.almostrealism.util.Factory;
import org.jocl.CL;
import org.jocl.cl_kernel;
import org.jocl.cl_program;

public class GPUOperator<T extends MemWrapper> implements Operator<T>, Factory<cl_kernel> {
	private cl_program prog;
	private String name;

	public GPUOperator(cl_program program, String name) {
		this.prog = program;
		this.name = name;
	}

	// TODO  How do these kernels get released when done?
	@Override
	public cl_kernel construct() { return CL.clCreateKernel(prog, name, null); }

	@Override
	public Scope<? extends Variable> getScope(String prefix) {
		return null;
	}

	@Override
	public T evaluate(Object[] args) {
		return null;
	}

	@Override
	public void compact() {

	}
}
