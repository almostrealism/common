/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware.cl;

import org.almostrealism.hardware.HardwareException;
import org.jocl.CLException;

public class MismatchedContextException extends HardwareException {
	public MismatchedContextException(CLDataContext targetContext, CLDataContext actualContext, CLException cause) {
		super("Attempting to use " + name(targetContext) + " when " + name(actualContext) + " is in effect", cause);
	}

	public static String name(CLDataContext ctx) {
		String name = String.valueOf(ctx.toString());
		if (name.contains(".")) {
			name = name.substring(name.lastIndexOf(".") + 1);
		}

		return name;
	}
}
