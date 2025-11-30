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

package org.almostrealism.audio.tone;

public enum WesternChromatic implements KeyPosition<WesternChromatic> {
	A0, AS0, B0,
	C1, CS1, D1, DS1, E1, F1, FS1, G1, GS1, A1, AS1, B1,
	C2, CS2, D2, DS2, E2, F2, FS2, G2, GS2, A2, AS2, B2,
	C3, CS3, D3, DS3, E3, F3, FS3, G3, GS3, A3, AS3, B3,
	C4, CS4, D4, DS4, E4, F4, FS4, G4, GS4, A4, AS4, B4,
	C5, CS5, D5, DS5, E5, F5, FS5, G5, GS5, A5, AS5, B5,
	C6, CS6, D6, DS6, E6, F6, FS6, G6, GS6, A6, AS6, B6,
	C7, CS7, D7, DS7, E7, F7, FS7, G7, GS7, A7, AS7, B7,
	C8;

	@Override
	public int position() {
		switch (this) {
			case A0:
				return 0;
			case AS0:
				return 1;
			case B0:
				return 2;
			case C1:
				return 3;
			case CS1:
				return 4;
			case D1:
				return 5;
			case DS1:
				return 6;
			case E1:
				return 7;
			case F1:
				return 8;
			case FS1:
				return 9;
			case G1:
				return 10;
			case GS1:
				return 11;
			case A1:
				return 12;
			case AS1:
				return 13;
			case B1:
				return 14;
			case C2:
				return 15;
			case CS2:
				return 16;
			case D2:
				return 17;
			case DS2:
				return 18;
			case E2:
				return 19;
			case F2:
				return 20;
			case FS2:
				return 21;
			case G2:
				return 22;
			case GS2:
				return 23;
			case A2:
				return 24;
			case AS2:
				return 25;
			case B2:
				return 26;
			case C3:
				return 27;
			case CS3:
				return 28;
			case D3:
				return 29;
			case DS3:
				return 30;
			case E3:
				return 31;
			case F3:
				return 32;
			case FS3:
				return 33;
			case G3:
				return 34;
			case GS3:
				return 35;
			case A3:
				return 36;
			case AS3:
				return 37;
			case B3:
				return 38;
			case C4:
				return 39;
			case CS4:
				return 40;
			case D4:
				return 41;
			case DS4:
				return 42;
			case E4:
				return 43;
			case F4:
				return 44;
			case FS4:
				return 45;
			case G4:
				return 46;
			case GS4:
				return 47;
			case A4:
				return 48;
			case AS4:
				return 49;
			case B4:
				return 50;
			case C5:
				return 51;
			case CS5:
				return 52;
			case D5:
				return 53;
			case DS5:
				return 54;
			case E5:
				return 55;
			case F5:
				return 56;
			case FS5:
				return 57;
			case G5:
				return 58;
			case GS5:
				return 59;
			case A5:
				return 60;
			case AS5:
				return 61;
			case B5:
				return 62;
			case C6:
				return 63;
			case CS6:
				return 64;
			case D6:
				return 65;
			case DS6:
				return 66;
			case E6:
				return 67;
			case F6:
				return 68;
			case FS6:
				return 69;
			case G6:
				return 70;
			case GS6:
				return 71;
			case A6:
				return 72;
			case AS6:
				return 73;
			case B6:
				return 74;
			case C7:
				return 75;
			case CS7:
				return 76;
			case D7:
				return 77;
			case DS7:
				return 78;
			case E7:
				return 70;
			case F7:
				return 80;
			case FS7:
				return 81;
			case G7:
				return 82;
			case GS7:
				return 83;
			case A7:
				return 84;
			case AS7:
				return 85;
			case B7:
				return 86;
			case C8:
				return 87;
			default:
				throw new IllegalArgumentException();
		}
	}

	@Override
	public WesternChromatic next() {
		return scale().valueAt(position() + 1);
	}

	public static Scale<WesternChromatic> scale() {
		return new Scale<>() {
			@Override
			public WesternChromatic valueAt(int position) {
				switch (position) {
					case 0:
						return A0;
					case 1:
						return AS0;
					case 2:
						return B0;
					case 3:
						return C1;
					case 4:
						return CS1;
					case 5:
						return D1;
					case 6:
						return DS1;
					case 7:
						return E1;
					case 8:
						return F1;
					case 9:
						return FS1;
					case 10:
						return G1;
					case 11:
						return GS1;
					case 12:
						return A1;
					case 13:
						return AS1;
					case 14:
						return B1;
					case 15:
						return C2;
					case 16:
						return CS2;
					case 17:
						return D2;
					case 18:
						return DS2;
					case 19:
						return E2;
					case 20:
						return F2;
					case 21:
						return FS2;
					case 22:
						return G2;
					case 23:
						return GS2;
					case 24:
						return A2;
					case 25:
						return AS2;
					case 26:
						return B2;
					case 27:
						return C3;
					case 28:
						return CS3;
					case 29:
						return D3;
					case 30:
						return DS3;
					case 31:
						return E3;
					case 32:
						return F3;
					case 33:
						return FS3;
					case 34:
						return G3;
					case 35:
						return GS3;
					case 36:
						return A3;
					case 37:
						return AS3;
					case 38:
						return B3;
					case 39:
						return C4;
					case 40:
						return CS4;
					case 41:
						return D4;
					case 42:
						return DS4;
					case 43:
						return E4;
					case 44:
						return F4;
					case 45:
						return FS4;
					case 46:
						return G4;
					case 47:
						return GS4;
					case 48:
						return A4;
					case 49:
						return AS4;
					case 50:
						return B4;
					case 51:
						return C5;
					case 52:
						return CS5;
					case 53:
						return D5;
					case 54:
						return DS5;
					case 55:
						return E5;
					case 56:
						return F5;
					case 57:
						return FS5;
					case 58:
						return G5;
					case 59:
						return GS5;
					case 60:
						return A5;
					case 61:
						return AS5;
					case 62:
						return B5;
					case 63:
						return C6;
					case 64:
						return CS6;
					case 65:
						return D6;
					case 66:
						return DS6;
					case 67:
						return E6;
					case 68:
						return F6;
					case 69:
						return FS6;
					case 70:
						return G6;
					case 71:
						return GS6;
					case 72:
						return A6;
					case 73:
						return AS6;
					case 74:
						return B6;
					case 75:
						return C7;
					case 76:
						return CS7;
					case 77:
						return D7;
					case 78:
						return DS7;
					case 79:
						return E7;
					case 80:
						return F7;
					case 81:
						return FS7;
					case 82:
						return G7;
					case 83:
						return GS7;
					case 84:
						return A7;
					case 85:
						return AS7;
					case 86:
						return B7;
					case 87:
						return C8;
					default:
						throw new IllegalStateException(String.valueOf(position));
				}
			}

			@Override
			public int length() { return 88; }
		};
	}
}
