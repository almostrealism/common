/*
 * Copyright 2018 Michael Murray
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

package io.flowtree.node;

import java.io.IOException;

/**
 * An implementation of the Proxy interface provides a method
 * for sending and receiving messages (objects) using IO streams
 * and an integer id. This allows for sharing of a socket connection
 * between a number of separate clients with unique id numbers.
 * A Proxy implementation must keep a FIFO queue of received objects
 * and return them based on ID.
 * 
 * @author Mike Murray
 */
public interface Proxy {
    /**
     * Writes the specified object to the output stream using
     * the specified id.
     * 
     * @param o  Object to write.
     * @param id  Unique id of reciever.
     */
	void writeObject(Object o, int id) throws IOException;
    
    /**
     * Returns the next object in the queue with the specified id.
     * 
     * @param id  Unique id of the receiver.
     * @return  The received object or null if one is not found.
     */
	Object nextObject(int id);
}
