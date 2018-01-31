package org.almostrealism.time;

import java.util.ArrayList;
import java.util.List;

public abstract class ClockSynchronizer implements Runnable {
	private long pause;
	private boolean stopped;
	private List<Listener> listeners;

	public ClockSynchronizer() {
		this(600000);
	}

	public ClockSynchronizer(long pause) {
		this.pause = pause;
		this.listeners = new ArrayList<>();
	}

	public void addListener(Listener l) {
		this.listeners.add(l);
	}

	public void run() {
		w: while (!stopped) {
			long t1 = System.currentTimeMillis();
			long t2 = getTime();
			long t3 = System.currentTimeMillis();

			if (t2 < 0) {
				System.err.println("Time unavailable, clock was not synchronized");
				continue w;
			}

			for (Listener l : listeners) {
				l.timeEvent(t1, t3, t2);
			}

			try {
				Thread.sleep(pause);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	public abstract long getTime();

	public Thread start() {
		Thread t = new Thread(this);
		t.start();
		return t;
	}

	public void stop() { this.stopped = true; }

	public interface Listener {
		void timeEvent(long beforeRequest, long afterRequest, long reportedTime);
	}
}
