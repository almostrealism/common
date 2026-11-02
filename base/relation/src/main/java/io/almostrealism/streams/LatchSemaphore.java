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

package io.almostrealism.streams;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Semaphore} implementation backed by a {@link CountDownLatch}.
 *
 * <p>The latch counts down to zero when the guarded operation (or a set of parallel
 * sub-operations) completes. Callers blocked in {@link #waitFor()} are released once the
 * count reaches zero. This is the metadata-free completion latch used by
 * {@link Semaphore#all(java.util.List)}; a subclass may attach requester attribution (see
 * {@code io.almostrealism.concurrent.DefaultLatchSemaphore}).</p>
 *
 * <p>A merged member that fails is recorded with {@link #fail(Throwable)} and still counts
 * down, so the latch is never pinned by a failure; the first such failure is rethrown from
 * {@link #waitFor()} once the count reaches zero, so a group failure reaches the waiter
 * rather than being lost.</p>
 *
 * <p>When this latch is the composite {@link Semaphore#all(List, java.util.function.IntFunction)}
 * builds, it is counted down by the completions it stands for and knows them (see
 * {@link #compose(List)}), so a wait settles them directly rather than waiting for the latch to
 * be counted down.</p>
 *
 * <p>A latch is counted down by whoever performs the guarded work, never by a waiter, so it
 * reaches zero whether or not anything waits for it. {@link #onSettled(Runnable)} (and the
 * {@link #whenSettled(Runnable)} and {@link #onComplete(Runnable)} built on it) therefore
 * holds no thread while the work runs: the callback is registered with the latch and handed to
 * {@link Semaphore#CALLBACK_EXECUTOR} by the count-down that reaches zero. This is what lets a
 * backend start a dispatch when the dispatch it depends on settles, rather than parking a
 * thread in {@link #waitFor()} for every dispatch issued ahead of its dependency.</p>
 */
public class LatchSemaphore implements Semaphore {
	/** The underlying latch used for synchronization. */
	private final CountDownLatch latch;

	/** The first failure observed among merged members, rethrown by {@link #waitFor()}. */
	private final AtomicReference<Throwable> failure;

	/**
	 * The completions this latch is counted down for when it is the composite built by
	 * {@link Semaphore#all(List, java.util.function.IntFunction)}, or {@code null} for a plain
	 * latch.
	 */
	private volatile List<Semaphore> members;

	/**
	 * Constructs a semaphore whose {@link #waitFor()} returns after {@code count}
	 * {@link #countDown()} calls.
	 *
	 * @param count the number of {@link #countDown()} calls required before
	 *              {@link #waitFor()} returns
	 */
	public LatchSemaphore(int count) {
		this(new SettlingLatch(count), new AtomicReference<>());
	}

	/**
	 * Constructs a semaphore over a latch created elsewhere, whose {@link #waitFor()} returns
	 * once that latch reaches zero, whoever counts it down. Such a latch cannot report its own
	 * settlement, so {@link #onSettled(Runnable)} waits for it on a callback thread instead.
	 * A re-attributed view of an existing semaphore is made with
	 * {@link #LatchSemaphore(LatchSemaphore)}, which shares all of its settlement state.
	 *
	 * @param latch the latch whose count-down to zero settles this semaphore
	 */
	protected LatchSemaphore(CountDownLatch latch) {
		this(latch, new AtomicReference<>());
	}

	/**
	 * Constructs a view sharing all of another semaphore's settlement state: its latch, its
	 * failure reference and, when it is a composite, its members (see {@link #compose(List)}),
	 * so a re-attributed view still reports a failure recorded through the original and, for a
	 * composite, still settles the members directly when waited.
	 *
	 * @param shared the semaphore whose settlement state is reused
	 */
	protected LatchSemaphore(LatchSemaphore shared) {
		this(shared.latch, shared.failure);
		this.members = shared.members;
	}

	/**
	 * Constructs a semaphore over the given latch, rethrowing the failure recorded in the given
	 * reference from {@link #waitFor()}.
	 *
	 * @param latch   the latch whose count-down to zero settles this semaphore
	 * @param failure the reference holding the first recorded failure
	 */
	private LatchSemaphore(CountDownLatch latch, AtomicReference<Throwable> failure) {
		this.latch = latch;
		this.failure = failure;
	}

	/**
	 * Returns true once the latch has been counted down to zero, that is, once the guarded work
	 * has settled, successfully or not.
	 *
	 * @return whether the guarded work has settled
	 */
	public boolean isSettled() { return latch.getCount() == 0; }

	/**
	 * Decrements the latch count, releasing waiters once it reaches zero.
	 */
	public void countDown() { latch.countDown(); }

	/**
	 * Records a failure observed while merging a member's completion, so it can be rethrown
	 * from {@link #waitFor()} once the latch releases. Only the first failure is retained;
	 * later ones are ignored. A {@code null} argument is a no-op, so this may be called
	 * unconditionally from a settlement callback.
	 *
	 * @param t the failure to record, or {@code null} if the member completed normally
	 */
	public void fail(Throwable t) {
		if (t != null) failure.compareAndSet(null, t);
	}

	/**
	 * Makes this latch the composite of {@code members}, as
	 * {@link Semaphore#all(List, java.util.function.IntFunction)} builds it: the latch is counted
	 * down once for each member, when that member settles.
	 *
	 * <p>Each member counts this latch down from its {@link Semaphore#onSettled(Runnable)}
	 * callback, registered here as soon as the composite exists: that registration is what drives
	 * a member whose completion has to be requested (a Metal dispatch whose command buffer is still
	 * open, for example), and a member that is told of its own settlement holds no thread
	 * meanwhile. A member is settled rather than merely awaited, so one that fails still counts
	 * the latch down, after its failure is recorded with {@link #fail(Throwable)}; a single failure
	 * can therefore never pin the latch.</p>
	 *
	 * <p>Knowing the members also changes how {@link #waitFor()} waits: it settles each member
	 * itself, so the waiter is released by the last member's completion directly, instead of by
	 * the thread that observed that completion counting the latch down &mdash; one thread hand-off
	 * fewer on the path of every wait for a composite. The latch counted down by the members
	 * remains a valid completion for anything that observes it.</p>
	 *
	 * @param members the completions this composite stands for, one for each count of this latch
	 */
	void compose(List<Semaphore> members) {
		this.members = members;

		for (Semaphore member : members) {
			member.onSettled(() -> countDownFor(member));
		}
	}

	/**
	 * Counts this composite down for a member that has settled, first recording the member's
	 * failure if it failed.
	 *
	 * @param member a member of this composite that has completed or failed
	 */
	private void countDownFor(Semaphore member) {
		try {
			member.waitFor();
		} catch (Throwable t) {
			fail(t);
		} finally {
			countDown();
		}
	}

	/**
	 * Blocks until the guarded work has completed, then rethrows the first recorded failure.
	 *
	 * <p>For a composite (see {@link #compose(List)}) every member is settled in turn on the
	 * calling thread: a member whose wait fails is recorded like any merged failure, and an
	 * interrupt stops the wait early exactly as it does for the latch. A member wait that
	 * throws because the waiting thread was interrupted is treated as that interrupt, not as a
	 * failure of the member, so it neither reaches this waiter nor poisons later waits.
	 * Otherwise the latch itself is awaited.</p>
	 *
	 * <p>A wait that an interrupt ends early returns with the interrupt status set and does
	 * not rethrow a failure already recorded: the work has not settled, so a caller that
	 * repeats the wait (see {@link Semaphore#waitForUninterruptibly()}) receives the failure
	 * only once every member has.</p>
	 */
	@Override
	public void waitFor() {
		List<Semaphore> settle = members;

		if (settle == null) {
			try {
				latch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		} else {
			for (Semaphore member : settle) {
				if (Thread.currentThread().isInterrupted()) return;

				try {
					member.waitFor();
				} catch (Throwable e) {
					// A member that reports this waiter's interrupt as a failure has not failed:
					// recording it would rethrow it to every later waiter of the composite
					if (Thread.currentThread().isInterrupted()) return;
					fail(e);
				}
			}

			// The last member's wait may itself have been ended early by an interrupt
			if (Thread.currentThread().isInterrupted()) return;
		}

		Throwable t = failure.get();
		if (t == null) return;
		if (t instanceof RuntimeException) throw (RuntimeException) t;
		if (t instanceof Error) throw (Error) t;
		throw new RuntimeException(t);
	}

	/**
	 * Blocks until the latch has been counted down to zero, without rethrowing a recorded
	 * failure and without settling a composite's members.
	 *
	 * <p>This is the wait of the party that performs the guarded work, not of its consumers: a
	 * coordinator that must know every piece of the work has finished (to release resources
	 * held for it) while the failure it may have recorded with {@link #fail(Throwable)} belongs
	 * to the semaphore's waiters, who receive it from {@link #waitFor()}. An interrupt ends the
	 * wait early with the interrupt status set.</p>
	 *
	 * @return true once the latch has reached zero, false if the wait was interrupted
	 */
	public boolean awaitCountDown() {
		try {
			latch.await();
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Registers {@code r} to run on {@link Semaphore#CALLBACK_EXECUTOR} once the latch reaches
	 * zero, whether the guarded work completed or failed, without holding a thread meanwhile.
	 * The callback runs promptly, because the latch is counted down by the work itself. A latch
	 * that was not created by this class (one handed to {@link #LatchSemaphore(CountDownLatch)})
	 * cannot be observed this way, and is waited for on a callback thread instead, as the
	 * default does.
	 *
	 * @param r the callback to invoke once the guarded work has settled
	 */
	@Override
	public void onSettled(Runnable r) {
		if (latch instanceof SettlingLatch) {
			((SettlingLatch) latch).whenSettled(r);
		} else {
			Semaphore.super.onSettled(r);
		}
	}

	/**
	 * A {@link CountDownLatch} that runs the callbacks registered with it once it reaches zero.
	 * The callbacks belong to the latch rather than to a {@link LatchSemaphore}, so every view
	 * that shares the latch (a re-attributed completion, for example) observes the same
	 * settlement.
	 */
	private static final class SettlingLatch extends CountDownLatch {
		/** The callbacks waiting for zero, or {@code null} once they have been handed off. */
		private List<Runnable> callbacks = new ArrayList<>();

		/**
		 * @param count the number of count-downs before the latch reaches zero
		 */
		SettlingLatch(int count) {
			super(count);
		}

		@Override
		public void countDown() {
			super.countDown();
			if (getCount() == 0) settle();
		}

		/**
		 * Runs {@code r} once the latch has reached zero: immediately, on a callback thread, if
		 * it already has, and otherwise when the count-down that reaches zero hands it off.
		 *
		 * @param r the callback to run
		 */
		void whenSettled(Runnable r) {
			synchronized (this) {
				if (callbacks != null && getCount() > 0) {
					callbacks.add(r);
					return;
				}
			}

			CALLBACK_EXECUTOR.execute(r);
		}

		/** Hands every registered callback to a callback thread, once. */
		private void settle() {
			List<Runnable> settled;

			synchronized (this) {
				settled = callbacks;
				callbacks = null;
			}

			if (settled != null) settled.forEach(CALLBACK_EXECUTOR::execute);
		}
	}
}
