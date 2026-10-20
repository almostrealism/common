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

package org.almostrealism.hardware.cuda;

import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.streams.Semaphore;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The completion handle for one submission to a {@link CudaStreamRunner}: the CUDA analog of
 * {@link org.almostrealism.hardware.metal.MetalSemaphore}.
 *
 * <p>A submission may be launched immediately or held back behind a foreign dependency (see
 * {@link CudaStreamRunner#submit}), so the semaphore exists before the work reaches the stream.
 * The runner counts it down once its completion thread has observed the work finish on the GPU
 * (or the submission failed, which is recorded with {@link #fail(Throwable)} first) and has run
 * the submission's completion callback, so a caller returning from {@link #waitFor()} may rely on
 * every effect of that callback.</p>
 *
 * <p>Knowing its {@link #getRunner() runner} is what lets a dependent submission to the same
 * runner skip any ordering work: the runner's stream already executes in submission order. Its
 * {@link #getSequence() sequence number} is what lets two completions of the same runner merge
 * into one (see {@link #merge(Semaphore)}).</p>
 */
public class CudaSemaphore extends DefaultLatchSemaphore {
	/** The runner the work was submitted to. */
	private final CudaStreamRunner runner;

	/** The position of the submission among all submissions to {@link #runner}. */
	private final long sequence;

	/**
	 * Creates the completion handle for a new submission.
	 *
	 * @param requester metadata of the operation the submission belongs to, or {@code null}
	 * @param runner    the runner the work is submitted to
	 * @param sequence  the position of the submission among all submissions to the runner
	 */
	CudaSemaphore(OperationMetadata requester, CudaStreamRunner runner, long sequence) {
		super(requester, 1);
		this.runner = runner;
		this.sequence = sequence;
	}

	/**
	 * Creates a handle sharing the settlement state of another.
	 *
	 * @param requester metadata of the operation this completion is attributed to, or {@code null}
	 * @param runner    the runner the work was submitted to
	 * @param sequence  the position of the submission among all submissions to the runner
	 * @param latch     the shared settlement latch
	 * @param failure   the shared failure
	 */
	private CudaSemaphore(OperationMetadata requester, CudaStreamRunner runner, long sequence,
						  CountDownLatch latch, AtomicReference<Throwable> failure) {
		super(requester, latch, failure);
		this.runner = runner;
		this.sequence = sequence;
	}

	/** Returns the runner the work was submitted to. */
	public CudaStreamRunner getRunner() { return runner; }

	/** Returns the position of the submission among all submissions to its runner. */
	public long getSequence() { return sequence; }

	/** Returns true once the submission has settled, successfully or not. */
	public boolean isSettled() { return getLatch().getCount() == 0; }

	/**
	 * Blocks until the submission has completed on the GPU and its completion callback has run.
	 *
	 * <p>A submission that has already settled returns (or reports its failure) immediately from
	 * any thread, including the runner's completion thread: the completion thread settles
	 * submissions in order, so a callback may rely on an earlier submission's result. Only a wait
	 * for a submission that has not settled yet is rejected there, as it would wait for the
	 * completion thread itself.</p>
	 *
	 * @throws IllegalStateException if the submission has not settled and this is called from the
	 *                               runner's completion thread, which would otherwise wait for itself
	 */
	@Override
	public void waitFor() {
		if (!isSettled()) runner.requireOffCompletionThread();
		super.waitFor();
	}

	/**
	 * Merges with another completion of the same {@link CudaStreamRunner} into whichever of the
	 * two was submitted later. A runner settles its submissions in submission order (see
	 * {@link CudaStreamRunner#submit}), so the later one cannot settle before the earlier one has,
	 * and a dependent submission to the same runner is ordered after both by the stream alone.
	 * Completions of another runner, or of any other provider, are not merged.
	 *
	 * @param other another completion
	 * @return the later completion when both belong to this runner, otherwise {@code null}
	 */
	@Override
	public Semaphore merge(Semaphore other) {
		if (!(other instanceof CudaSemaphore)) return null;

		CudaSemaphore cuda = (CudaSemaphore) other;
		if (cuda.runner != runner) return null;

		return cuda.sequence > sequence ? cuda : this;
	}

	@Override
	public Semaphore withRequester(OperationMetadata requester) {
		return new CudaSemaphore(requester, runner, sequence, getLatch(), getFailure());
	}
}
