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

package org.almostrealism.light;

import org.almostrealism.physics.PhysicalConstants;

import java.util.ArrayList;

/**
 * A MercuryXenonLamp 
 * 
 * To Implement, organize data from http://www.pti-nj.com/obb_spectra.html
 * into a binary search tree. Each element is a range from the table, so 
 * pick a random number in that range and return it in the proper units.
 * @author  Sam Tepper
 */
public class MercuryXenonLamp extends LightBulb implements PhysicalConstants {
	public static double verbose = Math.pow(10.0, -7.0);
	
	/** EnergyTable = [starting energy value, percentage present] **/
	ArrayList EnergyTable = new ArrayList();

	public MercuryXenonLamp() {
		EnergyTable.add(new double[] {2.479, .199});
		EnergyTable.add(new double[] {2.431, .199});
		EnergyTable.add(new double[] {2.384, .199});
		EnergyTable.add(new double[] {2.755, .199});
		EnergyTable.add(new double[] {2.695, .199});
		EnergyTable.add(new double[] {2.638, .199});
		EnergyTable.add(new double[] {2.826, .229});
		EnergyTable.add(new double[] {2.213, .249});
		EnergyTable.add(new double[] {2.339, .249});
		EnergyTable.add(new double[] {3.024, .304});
		EnergyTable.add(new double[] {2.952, .319});
		EnergyTable.add(new double[] {3.646, .448});
		EnergyTable.add(new double[] {2.530, .483});
		EnergyTable.add(new double[] {3.179, .488});
		EnergyTable.add(new double[] {2.817, .498});
		EnergyTable.add(new double[] {2.101, .521});
		EnergyTable.add(new double[] {3.874, .538});
		EnergyTable.add(new double[] {3.262, .538});			
		EnergyTable.add(new double[] {3.542, .886});
		EnergyTable.add(new double[] {3.350, 1.473});
		EnergyTable.add(new double[] {2.254, 1.563});
		EnergyTable.add(new double[] {3.756, 1.572});
		EnergyTable.add(new double[] {.6887, 1.990});
		EnergyTable.add(new double[] {.6524, 1.990});
		EnergyTable.add(new double[] {1.771, 2.041});
		EnergyTable.add(new double[] {.7748, 2.051});
		EnergyTable.add(new double[] {.8855, 2.091});
		EnergyTable.add(new double[] {.7292, 2.190});
		EnergyTable.add(new double[] {1.033, 2.190});
		EnergyTable.add(new double[] {4.767, 2.205});
		EnergyTable.add(new double[] {.8264, 2.449});
		EnergyTable.add(new double[] {4.591, 2.678});
		EnergyTable.add(new double[] {3.099, 2.862});
		EnergyTable.add(new double[] {4.272, 2.877});
		EnergyTable.add(new double[] {1.377, 2.947});
		EnergyTable.add(new double[] {1.500, 3.186});
		EnergyTable.add(new double[] {2.296, 3.325});
		EnergyTable.add(new double[] {2.175, 3.463});
		EnergyTable.add(new double[] {2.066, 3.467});
		EnergyTable.add(new double[] {1.127, 3.544});
		EnergyTable.add(new double[] {4.132, 3.883});
		EnergyTable.add(new double[] {1.234, 3.942});
		EnergyTable.add(new double[] {4.274, 4.231});
		EnergyTable.add(new double[] {.9536, 4.580});
		EnergyTable.add(new double[] {2.137, 4.908});
		EnergyTable.add(new double[] {2.883, 5.207});
		EnergyTable.add(new double[] {3.998, 5.730});
		EnergyTable.add(new double[] {3.443, 8.408});
		
		super.specAvg = this.getSpecAvg();
		
		System.out.println("MercuryXenonLamp: Constructed!");
	}
	
	public double getSpecAvg(){
		double count = 0;
		
		for (int n = 0; n < EnergyTable.size(); n++) {
			count += ((double[])EnergyTable.get(n))[0];
		}
		
		return count/(EnergyTable.size()-1);
	}
	
	public int GetAverageIndex(ArrayList Al){
		//First, get the sum
		double sum=0;
		double avg=0;
		for (int i = 0; i < Al.size(); i++){
			sum += ((double[])Al.get(i))[1];
		}
		//Next, get the sum/2
		avg = sum/2.0;
		
		//Now, keep on adding up the values until the running total is <= the average
		int index = 0;
		double rtotal=0;
		while (rtotal < avg){
			rtotal += ( (double[]) Al.get(index) )[1];
			index++;
		}
		
		return index;
	}
	
	
	public double[][] GetValue() {
		ArrayList TempList = (ArrayList) EnergyTable.clone();
		ArrayList Value = new ArrayList();
		
		ArrayList list1 = new ArrayList();
		ArrayList list2 = new ArrayList();
		
		while(TempList.size() > 3){
			list1.clear();
			list2.clear();
			
			int avg = GetAverageIndex(TempList);
			
			for(int i = 0; i < avg; i++){
				list1.add(TempList.get(i));
			}
			
			for (int i = avg; i < TempList.size(); i++ ){
				list2.add(TempList.get(i));
			}
			
			if (Math.random() < 0.5) {
				TempList = (ArrayList) list1.clone();
				// System.out.println("List 1 chosen " + list1.size());
			} else {
				TempList = (ArrayList) list2.clone(); 
				// System.out.println("List 2 chosen " + list2.size());
			}
		}
		
		// System.out.println("MercuryXenonLamp: " + TempList.size());
		if (TempList.size() == 1){
			Value.add(TempList.get(0));
			return (double[][]) Value.toArray(new double[0][0]);
		}
		if (TempList.size() == 2){
			Value.add(TempList.get(((int)Math.random()*20) % 2));
			return (double[][]) Value.toArray(new double[0][0]);
		}
		
		if (TempList.size() == 3){
			Value.add(TempList.get(((int)Math.random()*30) % 3));
			return (double[][]) Value.toArray(new double[0][0]);
		}
		
		throw new RuntimeException();
		
	}
	
	/**
	 * getEmitEnergy() takes the value returned by GetValue() and then adds some random value
	 * between it's current value and the next greatest value in the table (which is what that
	 * long calculation is all about). The reason why there is an if statement is that because
	 * the website I got my data from used intervals of 10 nm from 260nm-700nm, then starting
	 * going in intervals of 100. 1.771 eV is the energy of a photon of wavelength 700nm.
	 */
	@Override
	public double getEmitEnergy() {
		double[][] V = GetValue();
		double Answer = 0.0;
		
		if (V[0][0] < 1.771){
			Answer = V[0][0]+Math.random()*(-10*HC/(Math.pow(V[0][0],2)-10*V[0][0]))+1;
		}
		else{
			Answer =  V[0][0]+(-100*HC/(Math.pow(V[0][0],2)-100*V[0][0])+1);
		}
		
		if (Math.random() < verbose)
			System.out.println("MercuryXenonLamp: " + Answer);
		
		return Answer;
		
	}
}