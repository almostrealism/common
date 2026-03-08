/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import java.util.List;
import java.util.function.Consumer;

/**
 * Callback interface for receiving sound data events from a {@link SoundDataHub}.
 *
 * <p>{@code SoundDataListener} receives notifications for sound data selection,
 * publication, channel updates, and playback control commands. Implementations
 * typically update audio player UI components or trigger audio processing.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SoundDataHub.getCurrent().addListener(new SoundDataListener() {
 *     @Override
 *     public void selected(SoundData d) {
 *         // Update waveform display
 *         waveformView.loadAudio(d.getFile());
 *     }
 *
 *     @Override
 *     public void published(int index, SoundData d) {
 *         // Update channel slot
 *         channels.get(index).setSoundData(d);
 *     }
 *
 *     @Override
 *     public void play() {
 *         audioPlayer.start();
 *     }
 * });
 * }</pre>
 *
 * <h2>Factory Method</h2>
 * <p>For simple selection-only listeners, use {@link #onSelected(Consumer)}:</p>
 * <pre>{@code
 * hub.addListener(SoundDataListener.onSelected(data -> {
 *     System.out.println("Selected: " + data.getFile());
 * }));
 * }</pre>
 *
 * @see SoundDataHub
 * @see SoundData
 */
public interface SoundDataListener {

	/**
	 * Called when the channel list has been updated.
	 *
	 * <p>Default implementation does nothing.</p>
	 *
	 * @param channelNames the new list of channel names
	 */
	default void updatedChannels(List<String> channelNames) { }

	/**
	 * Called when sound data has been selected.
	 *
	 * @param d the selected sound data
	 */
	void selected(SoundData d);

	/**
	 * Called when sound data has been published to a channel.
	 *
	 * @param index the channel index
	 * @param d     the published sound data
	 */
	void published(int index, SoundData d);

	/**
	 * Called when the play mode has changed.
	 *
	 * <p>Default implementation does nothing.</p>
	 *
	 * @param mode the new play mode
	 */
	default void setPlayMode(PlayMode mode) { }

	/**
	 * Called to start playback.
	 *
	 * <p>Default implementation does nothing.</p>
	 */
	default void play() { }

	/**
	 * Called to pause playback.
	 *
	 * <p>Default implementation does nothing.</p>
	 */
	default void pause() { }

	/**
	 * Called to seek to a specific time position.
	 *
	 * <p>Default implementation does nothing.</p>
	 *
	 * @param time the time position in seconds
	 */
	default void seek(double time) { }

	/**
	 * Called to toggle between play and pause states.
	 *
	 * <p>Default implementation does nothing.</p>
	 */
	default void togglePlay() { }

	/**
	 * Playback mode enumeration.
	 */
	enum PlayMode {
		/**
		 * Play all available audio in sequence.
		 */
		ALL,

		/**
		 * Play only the currently selected audio.
		 */
		SELECTED
	}

	/**
	 * Creates a simple listener that only handles selection events.
	 *
	 * <p>This is a convenience factory for listeners that only need to
	 * respond to selection events and can ignore publications and
	 * playback control.</p>
	 *
	 * @param consumer the consumer to call when sound data is selected
	 * @return a new SoundDataListener that delegates selection to the consumer
	 */
	static SoundDataListener onSelected(Consumer<SoundData> consumer) {
		return new SoundDataListener() {
			@Override
			public void selected(SoundData d) { consumer.accept(d); }

			@Override
			public void published(int index, SoundData d) { }
		};
	}
}
