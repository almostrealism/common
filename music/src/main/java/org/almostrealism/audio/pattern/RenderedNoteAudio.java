/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.audio.pattern;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

public class RenderedNoteAudio {
	private Producer<PackedCollection> producer;
	private int offset;

	public RenderedNoteAudio() {
		this(null, 0);
	}

	public RenderedNoteAudio(Producer<PackedCollection> producer, int offset) {
		this.producer = producer;
		this.offset = offset;
	}

	public Producer<PackedCollection> getProducer() {
		return producer;
	}

	public void setProducer(Producer<PackedCollection> producer) {
		this.producer = producer;
	}

	public int getOffset() {
		return offset;
	}

	public void setOffset(int offset) {
		this.offset = offset;
	}
}
