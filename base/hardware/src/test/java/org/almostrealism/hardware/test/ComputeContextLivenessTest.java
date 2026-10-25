/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.test;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.Precision;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.jni.NativeDataContext;
import org.junit.Assert;
import org.junit.Test;

/**
 * {@link ComputeContext#isLive()} reports whether work can still run under a compute context.
 * It stops holding once either the context itself or the data context its kernels dispatch
 * against has been destroyed, and the two are destroyed independently, so each half is checked
 * on its own.
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}: that
 * class lives in the engine layer, which sits above this module.</p>
 */
public class ComputeContextLivenessTest {

	/**
	 * Minimal {@link AbstractComputeContext} over a given data context; none of the members it
	 * must supply are read by the liveness check.
	 */
	private static class TestComputeContext extends AbstractComputeContext<DataContext<MemoryData>> {
		/**
		 * Creates a context over the given data context.
		 *
		 * @param dataContext the data context this context reports from {@link #getDataContext()}
		 */
		TestComputeContext(DataContext<MemoryData> dataContext) {
			super(dataContext);
		}

		@Override
		public LanguageOperations getLanguage() { return null; }

		@Override
		public InstructionSet deliver(Scope scope) { return null; }

		@Override
		public boolean isCPU() { return true; }
	}

	/**
	 * Destroying the data context ends the liveness of a compute context that has not itself
	 * been destroyed.
	 */
	@Test(timeout = 30000)
	public void notLiveOnceDataContextIsDestroyed() {
		NativeDataContext data = new NativeDataContext("test-native", Precision.FP64, 1024L * 1024);
		TestComputeContext context = new TestComputeContext(data);

		try {
			Assert.assertTrue("A context over an intact data context should be live", context.isLive());

			data.destroy();

			Assert.assertFalse("Destroying the data context does not destroy the compute context",
					context.isDestroyed());
			Assert.assertFalse("A context whose data context is destroyed must not be live",
					context.isLive());
		} finally {
			context.destroy();
		}
	}

	/** Destroying the compute context ends its liveness while its data context is intact. */
	@Test(timeout = 30000)
	public void notLiveOnceDestroyed() {
		NativeDataContext data = new NativeDataContext("test-native", Precision.FP64, 1024L * 1024);
		TestComputeContext context = new TestComputeContext(data);

		try {
			context.destroy();

			Assert.assertFalse("Destroying the compute context does not destroy its data context",
					data.isDestroyed());
			Assert.assertFalse("A destroyed context must not be live", context.isLive());
		} finally {
			data.destroy();
		}
	}
}
