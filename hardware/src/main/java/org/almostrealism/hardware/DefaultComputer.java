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

package org.almostrealism.hardware;

import io.almostrealism.code.Computation;
import io.almostrealism.code.Computer;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.generated.BaseGeneratedProducer;
import org.almostrealism.generated.BaseGeneratedOperation;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.jni.NativeSupport;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DefaultComputer implements Computer<MemoryData> {
	private static final List<Class> libs = new ArrayList<>();
	private static int runnableCount, evaluableCount;

	private NativeCompiler compiler;

	public DefaultComputer() { this(null); }

	public DefaultComputer(NativeCompiler compiler) {
		this.compiler = compiler;
	}

	public boolean isNative() { return compiler != null; }

	public synchronized void loadNative(Class cls, String code) throws IOException, InterruptedException {
		if (libs.contains(cls)) return;

		compiler.compileAndLoad(cls, code);
		libs.add(cls);
	}

	public synchronized void loadNative(NativeSupport lib) throws IOException, InterruptedException {
		if (libs.contains(lib.getClass())) return;

		compiler.compileAndLoad(lib.getClass(), lib.get());
		libs.add(lib.getClass());
	}

	@Override
	public Runnable compileRunnable(Computation<Void> c) {
		if (compiler == null) {
			return new AcceleratedComputationOperation<>(c, false);
		} else {
			try {
				BaseGeneratedOperation gen = (BaseGeneratedOperation)
												Class.forName("org.almostrealism.generated.GeneratedOperation" + runnableCount++)
													.getConstructor(Computation.class).newInstance(c);
				return gen.get();
			} catch (InstantiationException | IllegalAccessException | InvocationTargetException
					| NoSuchMethodException | ClassNotFoundException e) {
				throw new HardwareException(e.getMessage(), new UnsupportedOperationException(e));
			}
		}
	}

	@Override
	public <T extends MemoryData> Evaluable<T> compileProducer(Computation<T> c) {
		if (compiler == null) {
			return new AcceleratedComputationEvaluable<>(c);
		} else {
			try {
				BaseGeneratedProducer gen = (BaseGeneratedProducer)
						Class.forName("org.almostrealism.generated.GeneratedProducer" + evaluableCount++)
								.getConstructor(Computation.class).newInstance(c);
				return gen.get();
			} catch (InvocationTargetException e) {
				if (e.getCause() instanceof HardwareException) {
					throw (HardwareException) e.getCause();
				} else if (e.getCause() != null) {
					throw new HardwareException(e.getMessage(), new UnsupportedOperationException(e.getCause()));
				} else {
					throw new HardwareException(e.getMessage(), new UnsupportedOperationException(e));
				}
			} catch (InstantiationException | IllegalAccessException
					| NoSuchMethodException | ClassNotFoundException e) {
				throw new HardwareException(e.getMessage(), new UnsupportedOperationException(e));
			}
		}
	}

	@Override
	public <T> Optional<Computation<T>> decompile(Runnable r) {
		if (r instanceof AcceleratedComputationOperation) {
			return Optional.of(((AcceleratedComputationOperation) r).getComputation());
		} else {
			return Optional.empty();
		}
	}

	@Override
	public <T> Optional<Computation<T>> decompile(Evaluable<T> p) {
		if (p instanceof AcceleratedComputationEvaluable) {
			return Optional.of(((AcceleratedComputationEvaluable) p).getComputation());
		} else {
			return Optional.empty();
		}
	}
}
