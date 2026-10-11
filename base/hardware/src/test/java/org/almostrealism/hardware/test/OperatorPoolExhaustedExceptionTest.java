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

/**
 * Verifies {@link OperatorPoolExhaustedException#isInCauseChain(Throwable)}, the predicate
 * callers use to recognise a (frequently wrapped) operator-pool exhaustion as the terminal
 * condition it is.
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}: that
 * class lives in the engine layer, which sits above this module, so depending on it would
 * invert the module graph. The predicate under test needs no accelerator.</p>
 */
public class OperatorPoolExhaustedExceptionTest {

	/** A top-level exhaustion exception is recognised as its own cause chain. */
	@Test(timeout = 10000)
	public void directInstanceIsRecognised() {
		OperatorPoolExhaustedException exhausted =
				new OperatorPoolExhaustedException(new RuntimeException("synthetic"));
		Assert.assertTrue(OperatorPoolExhaustedException.isInCauseChain(exhausted));
	}

	/** An exhaustion buried beneath unrelated wrapping exceptions is still recognised. */
	@Test(timeout = 10000)
	public void wrappedInstanceIsRecognised() {
		Throwable wrapped = new IllegalStateException("outer",
				new RuntimeException("middle",
						new OperatorPoolExhaustedException(new RuntimeException("inner"))));
		Assert.assertTrue(OperatorPoolExhaustedException.isInCauseChain(wrapped));
	}

	/** A failure with no exhaustion anywhere in its chain is not recognised. */
	@Test(timeout = 10000)
	public void unrelatedChainIsNotRecognised() {
		Throwable unrelated = new RuntimeException("outer",
				new IllegalArgumentException("inner"));
		Assert.assertFalse(OperatorPoolExhaustedException.isInCauseChain(unrelated));
	}

	/** A {@code null} throwable is tolerated and reported as no exhaustion. */
	@Test(timeout = 10000)
	public void nullIsTolerated() {
		Assert.assertFalse(OperatorPoolExhaustedException.isInCauseChain(null));
	}
}
