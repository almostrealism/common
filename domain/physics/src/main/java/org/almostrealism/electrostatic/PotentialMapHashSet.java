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
 * Copyright (C) 2006  Mike Murray
 *
 *  All rights reserved.
 *  This document may not be reused without
 *  express written permission from Mike Murray.
 */

package org.almostrealism.electrostatic;

import java.util.HashSet;

/**
 * A {@link PotentialMapHashSet} is an implementation of {@link PotentialMapSet} that uses
 * a {@link HashSet} to store the child potential maps.
 * 
 * @author Michael Murray
 */
public class PotentialMapHashSet extends HashSet implements PotentialMapSet {

	public int addPotentialMap(PotentialMap m, double[] x) {
		// TODO Auto-generated method stub
		return 0;
	}

	public int removePotentialMaps(double[] x, double radius) {
		// TODO Auto-generated method stub
		return 0;
	}

	public int removePotentialMap(PotentialMap m) {
		// TODO Auto-generated method stub
		return 0;
	}

	public void setMaxProximity(double radius) {
		// TODO Auto-generated method stub

	}

	public double getMaxProximity() {
		// TODO Auto-generated method stub
		return 0;
	}

	public double getPotential(double[] p) {
		// TODO Auto-generated method stub
		return 0;
	}

}
