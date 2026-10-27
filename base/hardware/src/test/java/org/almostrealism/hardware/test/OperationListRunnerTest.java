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

import io.almostrealism.concurrent.Submittable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationTimingListener;
import io.almostrealism.profile.OperationWithInfo;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.OperationListRunner;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Verifies that an {@link OperationListRunner} is itself {@link Submittable}: it issues its
 * members chained on a prior completion and returns the group's completion without waiting for
 * it, so a runner nested in another runner, or issued repeatedly by a loop, is chained rather than
 * waited for. The members are recording stand-ins, so the order in which completions are handed
 * on and waited for is observed directly, with no device involved.
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}:
 * that class lives in the engine layer, which sits above this module.</p>
 */
public class OperationListRunnerTest {

	/** A completion that records how many times it was waited for. */
	private static class RecordedCompletion implements Semaphore {
		/** The number of calls to {@link #waitFor()}. */
		private int waits;

		@Override
		public void waitFor() { waits++; }
	}

	/** A completion whose wait fails, as the completion of failed device work does. */
	private static class FailedCompletion extends RecordedCompletion {
		/** The failure thrown by every wait. */
		private final RuntimeException failure = new IllegalStateException("device work failed");

		@Override
		public void waitFor() {
			super.waitFor();
			throw failure;
		}
	}

	/** A member whose submission fails, as a dispatch that cannot be issued does. */
	private static class FailingMember extends RecordingMember {
		/** The failure thrown by every submission. */
		private final RuntimeException failure = new IllegalArgumentException("dispatch failed");

		@Override
		public Semaphore submit(Semaphore dependsOn) {
			super.submit(dependsOn);
			throw failure;
		}
	}

	/** A member that records the completion it is chained on and returns its own completion. */
	private static class RecordingMember implements Runnable, Submittable {
		/** This member's completion. */
		private final RecordedCompletion completion = new RecordedCompletion();
		/** The completions this member was submitted with, in order. */
		private final List<Semaphore> received = new ArrayList<>();
		/** The number of times this member was run on the host instead of submitted. */
		private int runs;

		@Override
		public void run() { runs++; }

		@Override
		public Semaphore submit(Semaphore dependsOn) {
			received.add(dependsOn);
			return completion;
		}
	}

	/** A recording member that carries its own metadata, as a compiled operation does. */
	private static class DescribedMember extends RecordingMember implements OperationInfo {
		/** This member's metadata. */
		private final OperationMetadata metadata;

		/**
		 * Creates a member that carries the given metadata.
		 *
		 * @param metadata the member's metadata
		 */
		DescribedMember(OperationMetadata metadata) {
			this.metadata = metadata;
		}

		@Override
		public OperationMetadata getMetadata() { return metadata; }

		@Override
		public String describe() { return metadata.getShortDescription(); }
	}

	/**
	 * A timing listener that records each timed operation, the operation that requested it, and
	 * how many waits for the work ahead had already happened when its timing began.
	 */
	private static class RecordingTimings implements OperationTimingListener {
		/** The number of waits for the work ahead so far. */
		private final IntSupplier waits;
		/** The requester of each timed operation, in order. */
		private final List<OperationMetadata> requesters = new ArrayList<>();
		/** The metadata each timed operation was recorded under, in order. */
		private final List<OperationMetadata> operations = new ArrayList<>();
		/** The value of {@link #waits} as each timing began, in order. */
		private final List<Integer> waitsWhenTimed = new ArrayList<>();

		/**
		 * Creates a listener that observes the given wait count.
		 *
		 * @param waits the number of waits for the work ahead so far
		 */
		RecordingTimings(IntSupplier waits) {
			this.waits = waits;
		}

		@Override
		public long recordDuration(OperationMetadata requester, Runnable r) {
			waitsWhenTimed.add(waits.getAsInt());
			return OperationTimingListener.super.recordDuration(requester, r);
		}

		@Override
		public void recordDuration(OperationMetadata requester, OperationMetadata operation, long nanos) {
			requesters.add(requester);
			operations.add(operation);
		}
	}

	/**
	 * Creates a runner over the given members, with no compute requirements and no timing.
	 *
	 * @param members the members, in order
	 * @return the runner
	 */
	private static OperationListRunner runner(Runnable... members) {
		return new OperationListRunner(new OperationMetadata("runner", "runner"),
				List.of(members), null, null);
	}

	/**
	 * {@code submit} hands its dependency to the first member, chains every later member on the
	 * completion of the one before it, and returns the last completion without waiting for any.
	 */
	@Test(timeout = 30000)
	public void submitChainsMembersWithoutWaiting() {
		RecordingMember first = new RecordingMember();
		RecordingMember second = new RecordingMember();
		RecordingMember third = new RecordingMember();
		RecordedCompletion start = new RecordedCompletion();

		Semaphore completion = runner(first, second, third).submit(start);

		Assert.assertSame(third.completion, completion);
		Assert.assertEquals(List.of(start), first.received);
		Assert.assertEquals(List.of(first.completion), second.received);
		Assert.assertEquals(List.of(second.completion), third.received);
		Assert.assertEquals(0, start.waits + first.completion.waits
				+ second.completion.waits + third.completion.waits);
		Assert.assertEquals(0, first.runs + second.runs + third.runs);
	}

	/**
	 * A member that cannot be submitted runs on the host only after the work ahead of it has been
	 * waited for, and the member after it begins a new chain.
	 */
	@Test(timeout = 30000)
	public void hostMemberWaitsForTheWorkAheadOfIt() {
		RecordingMember before = new RecordingMember();
		RecordingMember after = new RecordingMember();
		List<Integer> waitsSeenByHost = new ArrayList<>();
		Runnable host = () -> waitsSeenByHost.add(before.completion.waits);

		Semaphore completion = runner(before, host, after).submit(null);

		Assert.assertEquals(List.of(1), waitsSeenByHost);
		Assert.assertEquals(1, before.completion.waits);
		Assert.assertEquals(1, after.received.size());
		Assert.assertNull(after.received.get(0));
		Assert.assertSame(after.completion, completion);
		Assert.assertEquals(0, after.completion.waits);
	}

	/**
	 * A runner nested in another is submitted like any other member: its members are chained
	 * between the members around it, and nothing is waited for.
	 */
	@Test(timeout = 30000)
	public void nestedRunnerIsChained() {
		RecordingMember first = new RecordingMember();
		RecordingMember innerFirst = new RecordingMember();
		RecordingMember innerSecond = new RecordingMember();
		RecordingMember last = new RecordingMember();

		Semaphore completion = runner(first, runner(innerFirst, innerSecond), last).submit(null);

		Assert.assertEquals(List.of(first.completion), innerFirst.received);
		Assert.assertEquals(List.of(innerFirst.completion), innerSecond.received);
		Assert.assertEquals(List.of(innerSecond.completion), last.received);
		Assert.assertSame(last.completion, completion);
		Assert.assertEquals(0, first.completion.waits + innerFirst.completion.waits
				+ innerSecond.completion.waits + last.completion.waits);
	}

	/**
	 * With a timing listener, every member records the time taken to issue it under its own
	 * metadata, requested by the runner, and is still chained on the member before it. A member
	 * that runs on the host is timed only once the work ahead of it has been waited for, so that
	 * wait is not attributed to it.
	 */
	@Test(timeout = 30000)
	public void timedSubmitAttributesEachMemberWithoutTheHostWait() {
		OperationMetadata submittedMetadata = new OperationMetadata("submitted", "submitted");
		RecordingMember submitted = new DescribedMember(submittedMetadata);
		OperationMetadata hostMetadata = new OperationMetadata("host", "host");
		List<Integer> waitsSeenByHost = new ArrayList<>();
		Runnable host = OperationWithInfo.RunnableWithInfo.of(hostMetadata,
				() -> waitsSeenByHost.add(submitted.completion.waits));
		RecordingMember after = new RecordingMember();
		RecordingTimings timings = new RecordingTimings(() -> submitted.completion.waits);
		OperationMetadata runnerMetadata = new OperationMetadata("timed", "timed");

		Semaphore completion = new OperationListRunner(runnerMetadata,
				List.of(submitted, host, after), null, timings).submit(null);

		Assert.assertEquals(List.of(runnerMetadata, runnerMetadata, runnerMetadata), timings.requesters);
		Assert.assertSame(submittedMetadata, timings.operations.get(0));
		Assert.assertSame(hostMetadata, timings.operations.get(1));
		Assert.assertEquals(List.of(0, 1, 1), timings.waitsWhenTimed);
		Assert.assertEquals(List.of(1), waitsSeenByHost);
		Assert.assertEquals(1, after.received.size());
		Assert.assertNull(after.received.get(0));
		Assert.assertSame(after.completion, completion);
	}

	/** {@code run} issues the whole chain and waits exactly once, for its last completion. */
	@Test(timeout = 30000)
	public void runWaitsOnceForTheGroup() {
		RecordingMember first = new RecordingMember();
		RecordingMember second = new RecordingMember();

		runner(first, second).run();

		Assert.assertEquals(1, first.received.size());
		Assert.assertNull(first.received.get(0));
		Assert.assertEquals(List.of(first.completion), second.received);
		Assert.assertEquals(0, first.completion.waits);
		Assert.assertEquals(1, second.completion.waits);
	}

	/**
	 * A member that fails to issue leaves the members before it in flight; {@code submit} waits
	 * for them before the failure propagates, rethrows that very failure, and issues nothing after
	 * the failing member.
	 */
	@Test(timeout = 30000)
	public void failedMemberSettlesTheWorkInFlight() {
		RecordingMember first = new RecordingMember();
		FailingMember failing = new FailingMember();
		RecordingMember after = new RecordingMember();

		try {
			runner(first, failing, after).submit(null);
			Assert.fail("Expected the member's failure to propagate");
		} catch (IllegalArgumentException e) {
			Assert.assertSame(failing.failure, e);
			Assert.assertEquals(0, e.getSuppressed().length);
		}

		Assert.assertEquals(List.of(first.completion), ((RecordingMember) failing).received);
		Assert.assertEquals(1, first.completion.waits);
		Assert.assertTrue(after.received.isEmpty());
		Assert.assertEquals(0, after.runs);
	}

	/**
	 * When the work in flight at a member's failure has itself failed, its failure is attached
	 * to the propagating one as suppressed instead of replacing it.
	 */
	@Test(timeout = 30000)
	public void failedMemberSuppressesTheFailureOfTheWorkInFlight() {
		FailedCompletion inFlight = new FailedCompletion();
		FailingMember failing = new FailingMember();

		try {
			runner(failing).submit(inFlight);
			Assert.fail("Expected the member's failure to propagate");
		} catch (IllegalArgumentException e) {
			Assert.assertSame(failing.failure, e);
			Assert.assertArrayEquals(new Throwable[] { inFlight.failure }, e.getSuppressed());
		}

		Assert.assertEquals(1, ((RecordedCompletion) inFlight).waits);
	}

	/** A member that fails as the first issued, with no dependency, has nothing to wait for. */
	@Test(timeout = 30000)
	public void failedFirstMemberWithoutDependencyPropagates() {
		FailingMember failing = new FailingMember();

		try {
			runner(failing).submit(null);
			Assert.fail("Expected the member's failure to propagate");
		} catch (IllegalArgumentException e) {
			Assert.assertSame(failing.failure, e);
			Assert.assertEquals(0, e.getSuppressed().length);
		}

		List<Semaphore> received = ((RecordingMember) failing).received;
		Assert.assertEquals(1, received.size());
		Assert.assertNull(received.get(0));
	}
}
