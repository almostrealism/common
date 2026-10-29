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

package org.almostrealism.hardware.ctx;

import io.almostrealism.streams.LatchSemaphore;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Validates {@link SubmissionStream}, the backend-independent coordination the Metal and CUDA
 * runners are built on, with a completion type that touches no device: ordinals, same-stream
 * ordering and merging, the off-stream-thread guard, admission versus close, and the hold bound.
 *
 * <p>Plain JUnit: {@code TestSuiteBase} lives in the engine layer, above this module. The test
 * shares the stream's package, as the coordination it exercises is protected: only a runner,
 * which is a stream, uses it.</p>
 */
public class SubmissionStreamTest {
	/** A device-free stream whose own thread is whichever thread the test says it is. */
	private static final class TestStream extends SubmissionStream<TestCompletion> {
		/** True while the test treats the calling thread as the stream's own thread. */
		private final AtomicBoolean onStream = new AtomicBoolean();

		/**
		 * Creates a stream.
		 *
		 * @param maxHeld             the hold bound
		 * @param streamThreadMessage the message a wait on the stream's own thread is rejected with
		 */
		private TestStream(int maxHeld, String streamThreadMessage) {
			super("test stream", TestCompletion.class, maxHeld, streamThreadMessage);
		}

		@Override
		protected boolean isStreamThread() { return onStream.get(); }
	}

	/** A device-free completion of a {@link SubmissionStream}. */
	private static final class TestCompletion extends LatchSemaphore implements StreamCompletion {
		/** The stream the completion belongs to. */
		private final SubmissionStream<TestCompletion> stream;
		/** The completion's ordinal. */
		private final long ordinal;

		/**
		 * Creates the completion of the stream's next submission.
		 *
		 * @param stream the stream to take the next ordinal of
		 */
		private TestCompletion(SubmissionStream<TestCompletion> stream) {
			super(1);
			this.stream = stream;
			this.ordinal = stream.nextOrdinal();
		}

		@Override
		public SubmissionStream<TestCompletion> getStream() { return stream; }

		@Override
		public long getOrdinal() { return ordinal; }
	}

	/**
	 * Creates a stream whose own thread is never the caller.
	 *
	 * @param maxHeld the hold bound
	 * @return a new stream
	 */
	private static TestStream stream(int maxHeld) {
		return new TestStream(maxHeld, "on stream thread");
	}

	/** Ordinals start at one and increase by one per submission. */
	@Test(timeout = 10000)
	public void ordinalsAreMonotonic() {
		TestStream s = stream(4);
		Assert.assertEquals(0, s.lastOrdinal());
		Assert.assertEquals(1, new TestCompletion(s).getOrdinal());
		Assert.assertEquals(2, new TestCompletion(s).getOrdinal());
		Assert.assertEquals(2, s.lastOrdinal());
	}

	/**
	 * Only a completion of the same stream is ordered by it; the later of two such completions
	 * stands for both, and a completion of another stream or provider does not merge.
	 */
	@Test(timeout = 10000)
	public void sameStreamOrderingAndMerge() {
		TestStream s = stream(4);
		TestStream other = stream(4);
		TestCompletion first = new TestCompletion(s);
		TestCompletion second = new TestCompletion(s);
		TestCompletion elsewhere = new TestCompletion(other);

		Assert.assertTrue(s.ordersAfter(first));
		Assert.assertFalse(s.ordersAfter(elsewhere));
		Assert.assertFalse(s.ordersAfter(new LatchSemaphore(1)));
		Assert.assertFalse(s.ordersAfter(null));

		Assert.assertSame(second, s.later(first, second));
		Assert.assertSame(second, s.later(second, first));
		Assert.assertNull(s.later(first, elsewhere));
		Assert.assertNull(s.later(first, new LatchSemaphore(1)));
		Assert.assertSame(first, s.own(first));
		Assert.assertNull(s.own(elsewhere));
	}

	/** A wait on the stream's own thread is rejected with the stream's message. */
	@Test(timeout = 10000)
	public void waitOnStreamThreadIsRejected() {
		TestStream s = new TestStream(1, "cannot wait here");

		s.requireOffStreamThread();
		s.onStream.set(true);

		try {
			s.requireOffStreamThread();
			Assert.fail("A wait on the stream's own thread must be rejected");
		} catch (IllegalStateException expected) {
			Assert.assertEquals("cannot wait here", expected.getMessage());
		}
	}

	/** Admission runs the work until the stream is closed, and the refusal handler afterwards. */
	@Test(timeout = 10000)
	public void closedStreamRefusesAdmission() {
		TestStream s = stream(1);
		Assert.assertEquals("admitted", s.admit(() -> "admitted", refusal -> "refused"));

		Assert.assertTrue(s.close());
		Assert.assertFalse("A second close does nothing", s.close());
		Assert.assertTrue(s.isClosed());

		String result = s.admit(() -> "admitted", refusal -> {
			Assert.assertTrue(refusal.getMessage().contains("test stream has been destroyed"));
			return "refused";
		});
		Assert.assertEquals("refused", result);
	}

	/** {@code closeIf} closes only when the runner reports idleness under the admission lock. */
	@Test(timeout = 10000)
	public void closeIfClosesOnlyWhenIdle() {
		TestStream s = stream(1);
		Assert.assertFalse(s.closeIf(() -> false));
		Assert.assertFalse(s.isClosed());
		Assert.assertTrue(s.closeIf(() -> true));
		Assert.assertTrue(s.isClosed());
		Assert.assertTrue("An already closed stream does not consult the runner", s.closeIf(() -> {
			throw new AssertionError("must not be consulted");
		}));
	}

	/**
	 * Admission excludes closing: a close requested while a submission is being admitted waits
	 * for the admission to finish, so no submission is admitted after the stream decided to close.
	 *
	 * @throws InterruptedException if interrupted while coordinating the threads
	 */
	@Test(timeout = 10000)
	public void closeWaitsForAdmissionInProgress() throws InterruptedException {
		TestStream s = stream(1);
		CountDownLatch admitting = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);

		Thread submitter = new Thread(() -> s.admit(() -> {
			admitting.countDown();
			try {
				finish.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return null;
		}, refusal -> null));
		submitter.start();
		admitting.await();

		Thread closer = new Thread(s::close);
		closer.start();
		closer.join(200);
		Assert.assertTrue("Close must wait for the admission in progress", closer.isAlive());

		finish.countDown();
		closer.join();
		submitter.join();
		Assert.assertTrue(s.isClosed());
	}

	/**
	 * Once the hold bound is reached, a waiter for room blocks until held work is released, and a
	 * release beyond what was held is rejected.
	 *
	 * @throws InterruptedException if interrupted while waiting for the waiter
	 */
	@Test(timeout = 10000)
	public void holdBoundBlocksUntilReleased() throws InterruptedException {
		TestStream s = stream(2);
		s.hold();
		Assert.assertTrue(s.hasHoldCapacity());
		s.hold();
		Assert.assertFalse(s.hasHoldCapacity());
		Assert.assertEquals(2, s.getHeldCount());

		CountDownLatch admitted = new CountDownLatch(1);
		Thread waiter = new Thread(() -> {
			s.awaitHoldCapacityUninterruptibly();
			admitted.countDown();
		});
		waiter.start();

		Assert.assertFalse("The waiter must wait while the bound is reached",
				admitted.await(200, TimeUnit.MILLISECONDS));

		waiter.interrupt();
		Assert.assertFalse("An interrupt must not end the wait for room",
				admitted.await(200, TimeUnit.MILLISECONDS));

		s.release();
		Assert.assertTrue(admitted.await(5, TimeUnit.SECONDS));
		waiter.join();

		s.release();
		Assert.assertEquals(0, s.getHeldCount());

		try {
			s.release();
			Assert.fail("Releasing more than was held must be rejected");
		} catch (IllegalStateException expected) {
			Assert.assertTrue(expected.getMessage().contains("No held work"));
		}
	}

	/**
	 * A release racing a submitter that is about to wait for room is never lost. With a bound of
	 * one, a submitter repeatedly waits for room and holds while another thread releases each hold
	 * as soon as it appears, so nearly every release races a waiter entering or leaving the wait.
	 * A lost wakeup leaves the submitter parked with nothing held, and the submitter never finishes.
	 *
	 * @throws InterruptedException if interrupted while joining the threads
	 */
	@Test(timeout = 30000)
	public void releaseRacingAWaiterIsNeverLost() throws InterruptedException {
		int rounds = 20000;
		TestStream s = stream(1);

		Thread submitter = new Thread(() -> {
			for (int i = 0; i < rounds; i++) {
				s.awaitHoldCapacityUninterruptibly();
				s.hold();
			}
		});

		Thread releaser = new Thread(() -> {
			for (int i = 0; i < rounds; i++) {
				while (s.getHeldCount() == 0) Thread.onSpinWait();
				s.release();
			}
		});

		submitter.setDaemon(true);
		releaser.setDaemon(true);
		submitter.start();
		releaser.start();
		submitter.join(20000);
		releaser.join(5000);

		Assert.assertFalse("The submitter must not be left waiting for a release it missed",
				submitter.isAlive());
		Assert.assertFalse("Every hold must have been released", releaser.isAlive());
		Assert.assertEquals(0, s.getHeldCount());
		Assert.assertTrue(s.hasHoldCapacity());
	}

	/** A stream must allow at least one held unit of work. */
	@Test(timeout = 10000)
	public void holdBoundMustBePositive() {
		try {
			stream(0);
			Assert.fail("A stream that can hold nothing must be rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("maxHeld"));
		}
	}
}
