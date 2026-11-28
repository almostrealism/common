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

package io.almostrealism.compute.test;

import io.almostrealism.compute.Process;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Pins how a supplier is presented as a {@link Process}. A parent presents each of its inputs
 * as a child with {@link Process#asProcess(Supplier)}, which keeps an input that is already a
 * process as itself, so that the optimization cascade sees its children, parallelism and
 * isolation; only a supplier that is not a process is wrapped. {@link Process#of(Supplier)}
 * instead always creates a new wrapper, because {@link Process#isolate()} relies on it to
 * separate a process from its parent. None of this compiles anything, so the tests hold on
 * any backend.
 */
public class ProcessPresentationTests extends TestSuiteBase {

	/** A process with chosen children and output size, which does no work. */
	private static final class Stage implements Process<Process<?, ?>, Runnable> {
		/** The output size of this stage. */
		private final long outputSize;

		/** The children of this stage. */
		private final List<Process<?, ?>> children;

		/**
		 * Creates a stage.
		 *
		 * @param outputSize the output size of the stage
		 * @param children   the children of the stage
		 */
		private Stage(long outputSize, Process<?, ?>... children) {
			this.outputSize = outputSize;
			this.children = List.of(children);
		}

		@Override
		public Collection<Process<?, ?>> getChildren() { return children; }

		@Override
		public long getOutputSize() { return outputSize; }

		@Override
		public Runnable get() { return () -> { }; }
	}

	/** A supplier that is already a process is presented as itself. */
	@Test(timeout = 10000)
	public void processIsPresentedAsItself() {
		Stage stage = new Stage(4, new Stage(1));
		Assert.assertSame(stage, Process.asProcess(stage));
	}

	/**
	 * A supplier that is not a process is presented by a wrapper that delegates to it and has
	 * no children.
	 */
	@Test(timeout = 10000)
	public void supplierIsPresentedByAWrapper() {
		Supplier<String> supplier = () -> "value";
		Process<?, String> process = Process.asProcess(supplier);

		Assert.assertNotSame(supplier, process);
		Assert.assertEquals("value", process.get());
		Assert.assertTrue(process.getChildren().isEmpty());
	}

	/**
	 * {@link Process#of(Supplier)} wraps even a supplier that is a process, keeping its children
	 * and output size, which is what makes the wrapper an isolation boundary.
	 */
	@Test(timeout = 10000)
	public void ofWrapsEvenAProcess() {
		Stage child = new Stage(1);
		Stage stage = new Stage(4, child);
		Process<Process<?, ?>, Runnable> wrapper = Process.of(stage);

		Assert.assertNotSame(stage, wrapper);
		Assert.assertEquals(List.of(child), new ArrayList<>(wrapper.getChildren()));
		Assert.assertEquals(4, wrapper.getOutputSize());
	}

	/**
	 * The children of an {@link OperationList} are its operations, with an operation that is a
	 * process presented as itself and any other operation wrapped in a process that delegates
	 * to it.
	 */
	@Test(timeout = 10000)
	public void operationListPresentsProcessesAsThemselves() {
		Stage stage = new Stage(4);
		Runnable work = () -> { };
		Supplier<Runnable> plain = () -> work;

		OperationList list = new OperationList();
		list.add(stage);
		list.add(plain);

		List<Process<?, ?>> children = new ArrayList<>(list.getChildren());
		Assert.assertEquals(2, children.size());
		Assert.assertSame(stage, children.get(0));
		Assert.assertNotSame(plain, children.get(1));
		Assert.assertSame(work, children.get(1).get());
		Assert.assertTrue(children.get(1).getChildren().isEmpty());
	}
}
