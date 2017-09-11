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

package org.almostrealism.io;

public interface PrintWriter {
	/** Increases the indent. */
	void moreIndent();
	
	/** Reduces the indent. */
	void lessIndent();
	
	/** Appends the specified String. */
	void print(String s);
	
	/** Appends the specified String, followed by a new line character. */
	void println(String s);
	
	/** Appends a new line character. */
	void println();
}
