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

import org.almostrealism.hardware.OperatorPoolExhaustedException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;

/**
 * Verifies {@link OperatorPoolExhaustedException#causeOf(Throwable)}, the lookup callers use
 * to recognise a (frequently wrapped) operator-pool exhaustion as the terminal condition it
 * is, and to obtain the exhaustion itself.
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}: that
 * class lives in the engine layer, which sits above this module, so depending on it would
 * invert the module graph. The lookup under test needs no accelerator.</p>
 */
public class OperatorPoolExhaustedExceptionTest {

	/** A top-level exhaustion exception is returned as its own cause. */
	@Test(timeout = 10000)
	public void directInstanceIsReturned() {
		OperatorPoolExhaustedException exhausted =
				new OperatorPoolExhaustedException(new RuntimeException("synthetic"));
		Optional<OperatorPoolExhaustedException> found =
				OperatorPoolExhaustedException.causeOf(exhausted);
		Assert.assertTrue(found.isPresent());
		Assert.assertSame(exhausted, found.get());
	}

	/** An exhaustion buried beneath unrelated wrapping exceptions is found and returned. */
	@Test(timeout = 10000)
	public void wrappedInstanceIsReturned() {
		OperatorPoolExhaustedException exhausted =
				new OperatorPoolExhaustedException(new RuntimeException("inner"));
		Throwable wrapped = new IllegalStateException("outer",
				new RuntimeException("middle", exhausted));
		Optional<OperatorPoolExhaustedException> found =
				OperatorPoolExhaustedException.causeOf(wrapped);
		Assert.assertTrue(found.isPresent());
		Assert.assertSame(exhausted, found.get());
	}

	/** When the chain holds more than one exhaustion, the outermost is returned. */
	@Test(timeout = 10000)
	public void outermostInstanceIsReturned() {
		OperatorPoolExhaustedException inner =
				new OperatorPoolExhaustedException(new RuntimeException("first"));
		OperatorPoolExhaustedException outer = new OperatorPoolExhaustedException(
				new RuntimeException("rewrapped", inner));
		Throwable wrapped = new IllegalStateException("top", outer);
		Assert.assertSame(outer, OperatorPoolExhaustedException.causeOf(wrapped).get());
	}

	/** A failure with no exhaustion anywhere in its chain yields an empty result. */
	@Test(timeout = 10000)
	public void unrelatedChainIsEmpty() {
		Throwable unrelated = new RuntimeException("outer",
				new IllegalArgumentException("inner"));
		Assert.assertFalse(OperatorPoolExhaustedException.causeOf(unrelated).isPresent());
	}

	/** A {@code null} throwable is tolerated and yields an empty result. */
	@Test(timeout = 10000)
	public void nullIsTolerated() {
		Assert.assertFalse(OperatorPoolExhaustedException.causeOf(null).isPresent());
	}
}
