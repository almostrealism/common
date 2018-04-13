/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.audio;

import org.almostrealism.graph.ProteinCache;

public class AudioProteinCache implements ProteinCache<Long> {
	public static int addWait = 0;
	
	public static int sampleRate = 24 * 1024; // 44100
	public static int bufferDuration = 100;
	
	public static int depth = Integer.MAX_VALUE;
	public static long convertToByte = depth / Byte.MAX_VALUE;
	
	private long data[] = new long[sampleRate * bufferDuration];
	private int cursor;
	
	public AudioProteinCache() { }
	
	public long addProtein(Long p) {
		tryWait();
		
		if (p == null) p = 0l;
		
		// Store the 64 bit value
		data[cursor] = p;
		
		// Also store the value as 8 bytes
		// insertIntoByteBuffer(cursor, p);
		
		// Instead flatten to one byte
		// byteData[cursor] = flatten(p);
		
		cursor++;
		
		long index = cursor - 1;
		cursor = cursor % data.length;
		return index;
	}
	
	public Long getProtein(long index) { return data[(int) index]; }
	
	public long[] getLongData() { return data; }
	
	private void tryWait() {
		if (addWait == 0) return;
		if (cursor % sampleRate != 0) return;
		
		try {
			Thread.sleep(addWait);
		} catch (InterruptedException e) { }
	}
}
