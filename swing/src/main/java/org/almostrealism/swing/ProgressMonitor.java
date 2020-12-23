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

package org.almostrealism.swing;

/**
  A ProgressMonitor object can be used to detect and monitor internal progress.
*/

public interface ProgressMonitor {
	/**
	  Returns the increment size used by this ProgressMonitor object.
	*/
	
	public int getIncrementSize();
	
	/**
	  Returns the total size of this ProgressMonitor object.
	*/
	
	public int getTotalSize();
	
	/**
	  Returns the increment of this ProgressMonitor object.
	*/
	
	public int getIncrement();
	
	/**
	  Increments this ProgressMonitor object.
	*/
	
	public void increment();
}
