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

package org.almostrealism.currency;

import org.almostrealism.econ.Currency;
import org.almostrealism.econ.Share;

public class ETH extends Share implements Currency {
	public ETH() { }

	public ETH(double amount) { super(amount); }

	public void setAmount(double amount) { super.setValue(amount); }

	public ETH multiply(double amount) { return new ETH(asDouble() * amount); }
}
