/*
 * Copyright 2016 Michael Murray
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

/*
 * Copyright (C) 2005-06  Mike Murray
 *
 *  All rights reserved.
 *  This document may not be reused without
 *  express written permission from Mike Murray.
 *
 */

package org.almostrealism.io;

import java.io.OutputStream;

/**
 * Interface for objects that can persist their state to an output stream.
 *
 * <p>Storable provides a simple contract for serialization. Implementing classes
 * should provide a corresponding method to restore state, typically via a constructor
 * or static factory method that accepts an {@link java.io.InputStream}.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyData implements Storable {
 *     private int value;
 *
 *     public MyData(int value) {
 *         this.value = value;
 *     }
 *
 *     // Constructor to restore from stream
 *     public MyData(InputStream in) throws IOException {
 *         DataInputStream dis = new DataInputStream(in);
 *         this.value = dis.readInt();
 *     }
 *
 *     @Override
 *     public void store(OutputStream out) {
 *         try {
 *             DataOutputStream dos = new DataOutputStream(out);
 *             dos.writeInt(value);
 *         } catch (IOException e) {
 *             throw new RuntimeException(e);
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author Michael Murray
 */
public interface Storable {
	/**
	 * Persists the state of this object to the specified output stream.
	 *
	 * @param out the output stream to write to
	 */
	void store(OutputStream out);
}
