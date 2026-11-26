/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.music.pattern;

import java.util.concurrent.CancellationException;

/**
 * A cooperative cancellation signal for an in-progress pattern render. A render polls the signal
 * at safe points (between notes) and stops promptly when cancellation has been requested, without
 * the request having to wait for the whole render to finish.
 *
 * <p>This replaces interrupting the rendering thread. Interrupting is coarse: it also aborts any
 * blocking wait the render is parked in — a native dispatch completion wait, for instance — which
 * an abandoned wait cannot reliably report as failed and which logs a spurious fault. A cooperative
 * signal cancels only the render loop and leaves such waits to finish normally.</p>
 *
 * <p>The render's cancellation point ({@link PatternFeatures#renderNotes}) is reached deep inside a
 * compiled render operation and cannot be handed the signal as a parameter, so the signal is bound
 * to the rendering thread with {@link #bind()} and read from there with {@link #currentlyCancelled()}
 * — the same thread-scoped ambient access that the interrupt status provided, but for a signal the
 * canceller owns rather than the thread. The canceller holds the instance and calls {@link #cancel()}
 * from another thread; the render thread reads the request through the {@code volatile} flag.</p>
 */
public class PatternRenderCancellation {

	/** The signal governing the render on the current thread, or {@code null} when none is bound. */
	private static final ThreadLocal<PatternRenderCancellation> bound = new ThreadLocal<>();

	/** Whether cancellation of this signal's render has been requested. */
	private volatile boolean cancelled;

	/**
	 * Requests cancellation of the render governed by this signal. The request is sticky: once
	 * made, {@link #isCancelled()} and {@link #currentlyCancelled()} keep reporting it, so the
	 * canceller still observes the request after the render has stopped.
	 */
	public void cancel() {
		cancelled = true;
	}

	/**
	 * Returns whether cancellation has been requested for this signal.
	 *
	 * @return {@code true} once {@link #cancel()} has been called
	 */
	public boolean isCancelled() {
		return cancelled;
	}

	/**
	 * Binds this signal to the current thread so a render running on that thread can poll it via
	 * {@link #currentlyCancelled()} without threading the signal through its call chain. Pair with
	 * {@link #unbind()} in a {@code finally} block so the thread does not retain the signal once
	 * the render ends.
	 */
	public void bind() {
		bound.set(this);
	}

	/**
	 * Clears the current thread's bound signal when it is this one, leaving any other thread's
	 * binding untouched.
	 */
	public void unbind() {
		if (bound.get() == this) {
			bound.remove();
		}
	}

	/**
	 * Returns whether the render running on the current thread has been cancelled, reading the
	 * signal bound by {@link #bind()}. A thread with no bound signal is never cancelled.
	 *
	 * @return {@code true} if a signal is bound to the current thread and has been cancelled
	 */
	public static boolean currentlyCancelled() {
		PatternRenderCancellation current = bound.get();
		return current != null && current.cancelled;
	}

	/**
	 * Throws {@link CancellationException} when the render running on the current thread has been
	 * cancelled, and does nothing otherwise. This is the cancellation poll a render invokes at its
	 * safe points (between notes, between batched sub-window dispatches) so a single definition of
	 * the check and its message serves every point rather than each repeating it.
	 *
	 * @param frame the frame the render has reached, named in the exception message
	 * @throws CancellationException if a signal is bound to the current thread and has been cancelled
	 */
	public static void requireNotCancelled(int frame) {
		if (currentlyCancelled()) {
			throw new CancellationException("Pattern rendering cancelled at frame " + frame);
		}
	}
}
