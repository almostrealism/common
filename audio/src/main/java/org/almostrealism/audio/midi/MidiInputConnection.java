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
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Transmitter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wraps a connection to a single MIDI input device.
 * <p>
 * MidiInputConnection handles opening the device, receiving messages via
 * {@link Receiver}, parsing them, and dispatching to registered
 * {@link MidiInputListener} instances.
 * <p>
 * Usage:
 * <pre>{@code
 * MidiDevice device = ...;
 * MidiInputConnection connection = new MidiInputConnection(device);
 * connection.addListener(new MidiInputListener() {
 *     @Override
 *     public void noteOn(int channel, int note, int velocity) {
 *         System.out.println("Note on: " + note);
 *     }
 * });
 * connection.open();
 * // ... use connection ...
 * connection.close();
 * }</pre>
 *
 * @see MidiInputListener
 * @see MidiDeviceManager
 */
public class MidiInputConnection implements AutoCloseable {

	private final MidiDevice device;
	private final List<MidiInputListener> listeners;
	private Transmitter transmitter;
	private boolean open;

	/**
	 * Creates a connection wrapper for the specified MIDI device.
	 * The device is not opened until {@link #open()} is called.
	 *
	 * @param device the MIDI device to connect to
	 */
	public MidiInputConnection(MidiDevice device) {
		this.device = device;
		this.listeners = new CopyOnWriteArrayList<>();
		this.open = false;
	}

	/**
	 * Opens the MIDI device and starts receiving messages.
	 *
	 * @throws MidiUnavailableException if the device cannot be opened
	 */
	public void open() throws MidiUnavailableException {
		if (open) {
			return;
		}

		if (!device.isOpen()) {
			device.open();
		}

		transmitter = device.getTransmitter();
		transmitter.setReceiver(new MidiReceiver());
		open = true;
	}

	/**
	 * Closes the connection and releases the device.
	 */
	@Override
	public void close() {
		if (!open) {
			return;
		}

		open = false;

		if (transmitter != null) {
			transmitter.close();
			transmitter = null;
		}

		if (device.isOpen()) {
			device.close();
		}
	}

	/**
	 * Returns true if the connection is currently open.
	 */
	public boolean isOpen() {
		return open && device.isOpen();
	}

	/**
	 * Returns information about the connected device.
	 */
	public MidiDevice.Info getDeviceInfo() {
		return device.getDeviceInfo();
	}

	/**
	 * Returns the underlying MIDI device.
	 */
	public MidiDevice getDevice() {
		return device;
	}

	/**
	 * Adds a listener to receive MIDI messages.
	 *
	 * @param listener the listener to add
	 */
	public void addListener(MidiInputListener listener) {
		if (listener != null && !listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	/**
	 * Removes a listener.
	 *
	 * @param listener the listener to remove
	 */
	public void removeListener(MidiInputListener listener) {
		listeners.remove(listener);
	}

	/**
	 * Returns the number of registered listeners.
	 */
	public int getListenerCount() {
		return listeners.size();
	}

	/**
	 * Internal receiver that parses MIDI messages and dispatches to listeners.
	 */
	private class MidiReceiver implements Receiver {

		@Override
		public void send(MidiMessage message, long timeStamp) {
			if (!(message instanceof ShortMessage)) {
				// Ignore SysEx and other message types for now
				return;
			}

			ShortMessage sm = (ShortMessage) message;
			int command = sm.getCommand();
			int channel = sm.getChannel();
			int data1 = sm.getData1();
			int data2 = sm.getData2();

			switch (command) {
				case ShortMessage.NOTE_ON:
					if (data2 > 0) {
						for (MidiInputListener listener : listeners) {
							listener.noteOn(channel, data1, data2);
						}
					} else {
						// Note-on with velocity 0 is equivalent to note-off
						for (MidiInputListener listener : listeners) {
							listener.noteOff(channel, data1, 0);
						}
					}
					break;

				case ShortMessage.NOTE_OFF:
					for (MidiInputListener listener : listeners) {
						listener.noteOff(channel, data1, data2);
					}
					break;

				case ShortMessage.CONTROL_CHANGE:
					for (MidiInputListener listener : listeners) {
						listener.controlChange(channel, data1, data2);
					}
					break;

				case ShortMessage.PITCH_BEND:
					// Pitch bend is 14-bit: LSB in data1, MSB in data2
					int bendValue = (data2 << 7) | data1;
					for (MidiInputListener listener : listeners) {
						listener.pitchBend(channel, bendValue);
					}
					break;

				case ShortMessage.PROGRAM_CHANGE:
					for (MidiInputListener listener : listeners) {
						listener.programChange(channel, data1);
					}
					break;

				case ShortMessage.CHANNEL_PRESSURE:
					for (MidiInputListener listener : listeners) {
						listener.aftertouch(channel, data1);
					}
					break;

				case ShortMessage.POLY_PRESSURE:
					for (MidiInputListener listener : listeners) {
						listener.polyAftertouch(channel, data1, data2);
					}
					break;

				case ShortMessage.TIMING_CLOCK:
					for (MidiInputListener listener : listeners) {
						listener.clock();
					}
					break;

				case ShortMessage.START:
					for (MidiInputListener listener : listeners) {
						listener.start();
					}
					break;

				case ShortMessage.STOP:
					for (MidiInputListener listener : listeners) {
						listener.stop();
					}
					break;

				case ShortMessage.CONTINUE:
					for (MidiInputListener listener : listeners) {
						listener.midiContinue();
					}
					break;
			}
		}

		@Override
		public void close() {
			// Nothing to clean up
		}
	}
}
