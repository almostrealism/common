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

package org.almostrealism.graph.io;

import java.beans.DefaultPersistenceDelegate;
import java.beans.Encoder;
import java.beans.Statement;

/**
  A CameraPersistenceDelegate object adjusts the way the a Camera object is encoded into XML
  when using an XMLEncoder.
*/

public class CameraPersistenceDelegate extends DefaultPersistenceDelegate {
	/**
	  Properly encodes a Camera object.
	*/
	
	public void initialize(Class type, Object oldInstance, Object newInstance, Encoder out) {
		super.initialize(type, oldInstance, newInstance, out);
		
		out.writeStatement(new Statement(oldInstance, "updateUVW", new Object[0]));
	}
}
