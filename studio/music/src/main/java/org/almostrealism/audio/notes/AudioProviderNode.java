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

package org.almostrealism.audio.notes;

import org.almostrealism.audio.data.DelegateWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.WaveDataProvider;

import java.util.ArrayList;
import java.util.Collection;

public class AudioProviderNode implements NoteAudioNode {
	private String name;
	private String identifier;
	private boolean delegate;
	private int delegateOffset;
	private int length;

	public AudioProviderNode() { }

	protected AudioProviderNode(String name, String identifier) {
		this.name = name;
		this.identifier = identifier;
		this.delegate = false;
	}

	protected AudioProviderNode(String name, String identifier, int delegateOffset, int length) {
		this.name = name;
		this.identifier = identifier;
		this.delegate = true;
		this.delegateOffset = delegateOffset;
		this.length = length;
	}

	public void setName(String name) { this.name = name; }

	@Override
	public String getName() { return name; }

	@Override
	public String getIdentifier() { return identifier; }

	@Override
	public void setIdentifier(String identifier) { this.identifier = identifier; }

	public boolean isDelegate() { return delegate; }
	public void setDelegate(boolean delegate) { this.delegate = delegate; }

	public int getDelegateOffset() { return delegateOffset; }
	public void setDelegateOffset(int delegateOffset) { this.delegateOffset = delegateOffset; }

	public int getLength() { return length; }
	public void setLength(int length) { this.length = length; }

	@Override
	public Collection<NoteAudioNode> getChildren() {
		return new ArrayList<>();
	}

	public static AudioProviderNode create(WaveDataProvider provider) {
		if (provider instanceof FileWaveDataProvider) {
			return new AudioProviderNode(provider.getKey(), provider.getIdentifier());
		} else if (provider instanceof DelegateWaveDataProvider &&
				((DelegateWaveDataProvider) provider).getDelegate() instanceof FileWaveDataProvider p) {
			return new AudioProviderNode(p.getKey(), p.getIdentifier(),
					((DelegateWaveDataProvider) provider).getDelegateOffset(),
					provider.getCount());
		} else {
			return null;
		}
	}

	public static AudioProviderNode create(NoteAudioProvider note) {
		return create(note.getProvider());
	}
}
