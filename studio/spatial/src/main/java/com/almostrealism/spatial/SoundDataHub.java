/*
 * Copyright 2024 Michael Murray
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

package com.almostrealism.spatial;

import org.almostrealism.audio.AudioScene;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A publish/subscribe hub for sound data events with playback control support.
 *
 * <p>{@code SoundDataHub} coordinates communication between sound data producers
 * and consumers, typically used for audio player UI integration. It provides:</p>
 * <ul>
 *   <li>Sound data selection and publication events</li>
 *   <li>Channel list updates</li>
 *   <li>Playback control (play, pause, seek, toggle)</li>
 *   <li>Play mode switching (ALL vs SELECTED)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All operations are synchronized and the singleton instance obtained via
 * {@link #getCurrent()} executes listener callbacks asynchronously using a
 * dedicated executor thread to prevent UI blocking.</p>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * // Get async-safe singleton
 * SoundDataHub hub = SoundDataHub.getCurrent();
 *
 * // Register listener
 * hub.addListener(new SoundDataListener() {
 *     @Override
 *     public void selected(SoundData d) {
 *         // Handle selection
 *     }
 *
 *     @Override
 *     public void published(int index, SoundData d) {
 *         // Handle publication
 *     }
 * });
 *
 * // Control playback
 * hub.setPlayMode(SoundDataListener.PlayMode.ALL);
 * hub.play();
 * hub.seek(30.0); // Seek to 30 seconds
 * hub.pause();
 * }</pre>
 *
 * @see SoundData
 * @see SoundDataListener
 */
public class SoundDataHub implements ConsoleFeatures {
	private static ExecutorService executor = Executors.newSingleThreadExecutor();
	private static SoundDataHub current;

	private List<SoundDataListener> listeners;
	private SoundDataListener.PlayMode lastMode;

	/**
	 * Creates a new sound data hub with an empty listener list.
	 */
	public SoundDataHub() {
		this.listeners = new ArrayList<>();
	}

	/**
	 * Registers a listener to receive sound data events.
	 *
	 * @param listener the listener to add (must not be null)
	 * @throws IllegalArgumentException if listener is null
	 */
	public synchronized void addListener(SoundDataListener listener) {
		if (listener == null) {
			throw new IllegalArgumentException();
		}

		this.listeners.add(listener);
	}

	/**
	 * Removes a previously registered listener.
	 *
	 * @param listener the listener to remove
	 */
	public synchronized void removeListener(SoundDataListener listener) {
		this.listeners.remove(listener);
	}

	/**
	 * Notifies all listeners that the channel list has been updated.
	 *
	 * @param channels the new list of channel names
	 */
	public synchronized void updateChannels(List<String> channels) {
		listeners.forEach(l -> l.updatedChannels(channels));
	}

	/**
	 * Notifies all listeners that sound data has been selected.
	 *
	 * @param data the selected sound data
	 */
	public synchronized void selected(SoundData data) {
		listeners.forEach(l -> l.selected(data));
	}

	/**
	 * Notifies all listeners that sound data has been published to a channel.
	 *
	 * @param index the channel index
	 * @param data  the published sound data
	 */
	public synchronized void published(int index, SoundData data) {
		listeners.forEach(l -> l.published(index, data));
	}

	/**
	 * Sets the play mode and notifies all listeners.
	 *
	 * @param mode the play mode (ALL or SELECTED)
	 */
	public synchronized void setPlayMode(SoundDataListener.PlayMode mode) {
		listeners.forEach(l -> l.setPlayMode(mode));
		lastMode = mode;
	}

	/**
	 * Returns the current play mode.
	 *
	 * @return the last set play mode
	 */
	public SoundDataListener.PlayMode getPlayMode() { return lastMode; }

	/**
	 * Starts playback on all listeners.
	 */
	public synchronized void play() {
		listeners.forEach(SoundDataListener::play);
	}

	/**
	 * Pauses playback on all listeners.
	 */
	public synchronized void pause() {
		listeners.forEach(SoundDataListener::pause);
	}

	/**
	 * Seeks to a specific time position on all listeners.
	 *
	 * @param time the time position in seconds
	 */
	public synchronized void seek(double time) {
		listeners.forEach(l -> l.seek(time));
	}

	/**
	 * Toggles play/pause state on all listeners.
	 */
	public synchronized void togglePlay() {
		listeners.forEach(SoundDataListener::togglePlay);
	}

	/**
	 * Returns a thread-safe proxy to the singleton sound data hub.
	 *
	 * <p>The returned hub wraps all listener notifications in an executor
	 * to ensure they are processed asynchronously, preventing UI blocking.
	 * All operations are submitted to a single-threaded executor to maintain
	 * event ordering.</p>
	 *
	 * @return a proxy to the singleton hub with async notification
	 */
	public static SoundDataHub getCurrent() {
		if (current == null) current = new SoundDataHub();

		return new SoundDataHub() {
			@Override
			public synchronized void selected(SoundData data) {
				executor.submit(() -> {
					try {
						current.selected(data);
					} catch (Exception e) {
						e.printStackTrace();
					}
				});
			}

			@Override
			public synchronized void published(int index, SoundData data) {
				executor.submit(() -> {
					try {
						current.published(index, data);
					} catch (Exception e) {
						e.printStackTrace();
					}
				});
			}

			@Override
			public synchronized void setPlayMode(SoundDataListener.PlayMode mode) {
				executor.submit(() -> current.setPlayMode(mode));
			}

			@Override
			public SoundDataListener.PlayMode getPlayMode() {
				return current.getPlayMode();
			}

			@Override
			public void addListener(SoundDataListener listener) {
				if (listener == null) {
					throw new IllegalArgumentException();
				}

				executor.submit(() -> {
							try {
								current.addListener(listener);
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
				);
			}

			@Override
			public void removeListener(SoundDataListener listener) {
				executor.submit(() -> current.removeListener(listener));
			}

			@Override
			public void updateChannels(List<String> channels) {
				executor.submit(() -> current.updateChannels(channels));
			}

			@Override
			public void play() {
				executor.submit(() -> current.play());
			}

			@Override
			public void pause() {
				executor.submit(() -> current.pause());
			}

			public void seek(double time) {
				executor.submit(() -> current.seek(time));
			}

			@Override
			public void togglePlay() {
				executor.submit(() -> current.togglePlay());
			}
		};
	}

	@Override
	public Console console() {
		return AudioScene.console;
	}
}
