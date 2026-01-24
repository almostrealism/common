/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.midi;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages MIDI device enumeration and connections.
 * <p>
 * MidiDeviceManager provides:
 * <ul>
 *   <li>Enumeration of available MIDI input devices</li>
 *   <li>Opening connections to devices</li>
 *   <li>Optional hot-plug detection via polling</li>
 *   <li>Automatic reconnection for disconnected devices</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>{@code
 * MidiDeviceManager manager = new MidiDeviceManager();
 *
 * // List available devices
 * for (MidiDevice.Info info : manager.getAvailableInputDevices()) {
 *     System.out.println(info.getName());
 * }
 *
 * // Open a device
 * MidiInputConnection conn = manager.openInput(deviceInfo);
 * conn.addListener(myListener);
 *
 * // Enable hot-plug detection
 * manager.startDevicePolling(1000);
 * manager.addDeviceChangeListener(listener);
 *
 * // Clean up
 * manager.close();
 * }</pre>
 *
 * @see MidiInputConnection
 * @see MidiInputListener
 */
public class MidiDeviceManager implements AutoCloseable {

	private final Map<MidiDevice.Info, MidiInputConnection> openConnections;
	private final List<MidiDeviceChangeListener> deviceChangeListeners;
	private Set<MidiDevice.Info> lastKnownDevices;
	private Timer pollingTimer;
	private boolean closed;

	/**
	 * Creates a new MIDI device manager.
	 */
	public MidiDeviceManager() {
		this.openConnections = new HashMap<>();
		this.deviceChangeListeners = new CopyOnWriteArrayList<>();
		this.lastKnownDevices = new HashSet<>();
		this.closed = false;
	}

	/**
	 * Returns a list of available MIDI input devices.
	 * <p>
	 * Only devices that can provide MIDI input (have transmitters) are returned.
	 * Software synthesizers and output-only devices are excluded.
	 *
	 * @return list of available input device info objects
	 */
	public List<MidiDevice.Info> getAvailableInputDevices() {
		List<MidiDevice.Info> inputDevices = new ArrayList<>();
		MidiDevice.Info[] allDevices = MidiSystem.getMidiDeviceInfo();

		for (MidiDevice.Info info : allDevices) {
			try {
				MidiDevice device = MidiSystem.getMidiDevice(info);
				// A device with transmitters can provide input
				// maxTransmitters == -1 means unlimited, > 0 means has transmitters
				if (device.getMaxTransmitters() != 0) {
					// Exclude software synthesizers
					if (!isSoftwareSynth(device)) {
						inputDevices.add(info);
					}
				}
			} catch (MidiUnavailableException e) {
				// Device not available, skip it
			}
		}

		return inputDevices;
	}

	/**
	 * Returns a list of available MIDI output devices.
	 *
	 * @return list of available output device info objects
	 */
	public List<MidiDevice.Info> getAvailableOutputDevices() {
		List<MidiDevice.Info> outputDevices = new ArrayList<>();
		MidiDevice.Info[] allDevices = MidiSystem.getMidiDeviceInfo();

		for (MidiDevice.Info info : allDevices) {
			try {
				MidiDevice device = MidiSystem.getMidiDevice(info);
				// A device with receivers can accept output
				if (device.getMaxReceivers() != 0) {
					outputDevices.add(info);
				}
			} catch (MidiUnavailableException e) {
				// Device not available, skip it
			}
		}

		return outputDevices;
	}

	/**
	 * Opens a connection to the specified MIDI input device.
	 *
	 * @param deviceInfo the device to connect to
	 * @return the opened connection
	 * @throws MidiUnavailableException if the device cannot be opened
	 */
	public MidiInputConnection openInput(MidiDevice.Info deviceInfo) throws MidiUnavailableException {
		if (closed) {
			throw new IllegalStateException("MidiDeviceManager is closed");
		}

		// Check if already open
		MidiInputConnection existing = openConnections.get(deviceInfo);
		if (existing != null && existing.isOpen()) {
			return existing;
		}

		MidiDevice device = MidiSystem.getMidiDevice(deviceInfo);
		MidiInputConnection connection = new MidiInputConnection(device);
		connection.open();

		openConnections.put(deviceInfo, connection);
		return connection;
	}

	/**
	 * Closes a specific input connection.
	 *
	 * @param deviceInfo the device to disconnect
	 */
	public void closeInput(MidiDevice.Info deviceInfo) {
		MidiInputConnection connection = openConnections.remove(deviceInfo);
		if (connection != null) {
			connection.close();
		}
	}

	/**
	 * Returns an open connection for the specified device, or null if not open.
	 *
	 * @param deviceInfo the device info
	 * @return the connection or null
	 */
	public MidiInputConnection getConnection(MidiDevice.Info deviceInfo) {
		return openConnections.get(deviceInfo);
	}

	/**
	 * Adds a listener for device connection/disconnection events.
	 *
	 * @param listener the listener to add
	 */
	public void addDeviceChangeListener(MidiDeviceChangeListener listener) {
		if (listener != null && !deviceChangeListeners.contains(listener)) {
			deviceChangeListeners.add(listener);
		}
	}

	/**
	 * Removes a device change listener.
	 *
	 * @param listener the listener to remove
	 */
	public void removeDeviceChangeListener(MidiDeviceChangeListener listener) {
		deviceChangeListeners.remove(listener);
	}

	/**
	 * Starts polling for device changes at the specified interval.
	 * <p>
	 * Since javax.sound.midi doesn't provide hot-plug notifications,
	 * we poll to detect new and removed devices.
	 *
	 * @param intervalMs polling interval in milliseconds
	 */
	public void startDevicePolling(long intervalMs) {
		if (pollingTimer != null) {
			pollingTimer.cancel();
		}

		lastKnownDevices = new HashSet<>(getAvailableInputDevices());

		pollingTimer = new Timer("MidiDevicePoller", true);
		pollingTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				checkForDeviceChanges();
			}
		}, intervalMs, intervalMs);
	}

	/**
	 * Stops device polling.
	 */
	public void stopDevicePolling() {
		if (pollingTimer != null) {
			pollingTimer.cancel();
			pollingTimer = null;
		}
	}

	/**
	 * Checks for device changes and notifies listeners.
	 */
	private void checkForDeviceChanges() {
		Set<MidiDevice.Info> currentDevices = new HashSet<>(getAvailableInputDevices());

		// Find new devices
		for (MidiDevice.Info info : currentDevices) {
			if (!lastKnownDevices.contains(info)) {
				for (MidiDeviceChangeListener listener : deviceChangeListeners) {
					listener.deviceConnected(info);
				}
			}
		}

		// Find removed devices
		for (MidiDevice.Info info : lastKnownDevices) {
			if (!currentDevices.contains(info)) {
				for (MidiDeviceChangeListener listener : deviceChangeListeners) {
					listener.deviceDisconnected(info);
				}
			}
		}

		lastKnownDevices = currentDevices;
	}

	/**
	 * Closes all connections and stops polling.
	 */
	@Override
	public void close() {
		if (closed) {
			return;
		}

		closed = true;
		stopDevicePolling();

		for (MidiInputConnection connection : openConnections.values()) {
			connection.close();
		}
		openConnections.clear();
	}

	/**
	 * Checks if a device is a software synthesizer (which we want to exclude from input list).
	 */
	private boolean isSoftwareSynth(MidiDevice device) {
		String name = device.getDeviceInfo().getName().toLowerCase();
		String desc = device.getDeviceInfo().getDescription().toLowerCase();

		return name.contains("synth") ||
			   name.contains("java sound") ||
			   name.contains("gervill") ||
			   desc.contains("synthesizer") ||
			   device instanceof javax.sound.midi.Synthesizer;
	}

	/**
	 * Listener interface for device connection/disconnection events.
	 */
	public interface MidiDeviceChangeListener {
		/**
		 * Called when a new MIDI device is connected.
		 *
		 * @param deviceInfo the connected device
		 */
		void deviceConnected(MidiDevice.Info deviceInfo);

		/**
		 * Called when a MIDI device is disconnected.
		 *
		 * @param deviceInfo the disconnected device
		 */
		void deviceDisconnected(MidiDevice.Info deviceInfo);
	}
}
