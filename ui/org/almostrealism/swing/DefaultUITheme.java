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

import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;

import org.almostrealism.util.Defaults;

public class DefaultUITheme extends DefaultMetalTheme {
  private final ColorUIResource primary1 = new ColorUIResource(java.awt.Color.yellow);
  private final ColorUIResource primary2 = new ColorUIResource(java.awt.Color.blue.darker());
  private final ColorUIResource primary3 = new ColorUIResource(java.awt.Color.blue);
  
  private final ColorUIResource secondary1 = new ColorUIResource(69, 69, 69);
  private final ColorUIResource secondary2 = new ColorUIResource(120, 120, 120);
  private final ColorUIResource secondary3 = new ColorUIResource(171, 171, 171);

	public String getName() { return "Default App Theme"; }
	
	protected ColorUIResource getPrimary1() { return new ColorUIResource(Defaults.themePrimary1); }
	protected ColorUIResource getPrimary2() { return new ColorUIResource(Defaults.themePrimary2); }
	protected ColorUIResource getPrimary3() { return new ColorUIResource(Defaults.themePrimary3); }
	
	protected ColorUIResource getSecondary1() { return new ColorUIResource(Defaults.themeSecondary1); }
	protected ColorUIResource getSecondary2() { return new ColorUIResource(Defaults.themeSecondary2); }
	protected ColorUIResource getSecondary3() { return new ColorUIResource(Defaults.themeSecondary3); }
}
