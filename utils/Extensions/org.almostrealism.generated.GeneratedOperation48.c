#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
void f_fourierTransform_802_radix2(double *output, double *input, jint outputOffset, jint inputOffset, jint outputSize, jint inputSize, jint outputDim0, jint inputDim0, jint len, jint inverseTransform, jint isFirstSplit, jint global_id) {
double even_24[256];
double odd_25[256];
double evenFft_26[256];
double oddFft_27[256];
if (len >= 2) {
jint halfN_28 = len / 2;
double angle_29 = (M_PI * 2.0) / len;
if (inverseTransform == 0) {
angle_29 = - angle_29;
}
for (int k = 0; k < halfN_28;) {
jint kPlusHalfN_30 = k + halfN_28;
double angleK_31 = k * k;
double omegaR_32 = cos(angleK_31);
double omegaI_33 = sin(angleK_31);
even_24[k * 2] = input[(k * 2) + inputOffset] + input[(kPlusHalfN_30 * 2) + inputOffset];
even_24[(k * 2) + 1] = input[(k * 2) + inputOffset + 1] + input[(kPlusHalfN_30 * 2) + inputOffset + 1];
double inKMinusInKPlusHalfNr_34 = input[(k * 2) + inputOffset] - input[(kPlusHalfN_30 * 2) + inputOffset];
double inKMinusInKPlusHalfNi_35 = input[(k * 2) + inputOffset + 1] - input[(kPlusHalfN_30 * 2) + inputOffset + 1];
odd_25[k * 2] = (inKMinusInKPlusHalfNr_34 * omegaR_32) - (inKMinusInKPlusHalfNi_35 * omegaI_33);
odd_25[(k * 2) + 1] = (inKMinusInKPlusHalfNr_34 * omegaI_33) + (inKMinusInKPlusHalfNi_35 * omegaR_32);
k = k + 1;
}
f_fourierTransform_802_radix2(evenFft_26, even_24, 0, 0, 256, 256, 0, 0, halfN_28, inverseTransform, 0, global_id);
f_fourierTransform_802_radix2(oddFft_27, odd_25, 0, 0, 256, 256, 0, 0, halfN_28, inverseTransform, 0, global_id);
for (int k36 = 0; k36 < halfN_28;) {
jint doubleK_37 = k36 * 2;
if ((inverseTransform > 0) & (isFirstSplit > 0)) {
output[(doubleK_37 * 2) + outputOffset] = evenFft_26[k36 * 2] / len;
output[(doubleK_37 * 2) + outputOffset + 1] = evenFft_26[(k36 * 2) + 1] / len;
output[((doubleK_37 + 1) * 2) + outputOffset] = oddFft_27[k36 * 2] / len;
output[((doubleK_37 + 1) * 2) + outputOffset + 1] = oddFft_27[(k36 * 2) + 1] / len;
}
 else {
output[(doubleK_37 * 2) + outputOffset] = evenFft_26[k36 * 2];
output[(doubleK_37 * 2) + outputOffset + 1] = evenFft_26[(k36 * 2) + 1];
output[((doubleK_37 + 1) * 2) + outputOffset] = oddFft_27[k36 * 2];
output[((doubleK_37 + 1) * 2) + outputOffset + 1] = oddFft_27[(k36 * 2) + 1];
}
k36 = k36 + 1;
}
}
 else {
for (int i38 = 0; i38 < (len * 2);) {
output[i38 + outputOffset] = input[i38 + inputOffset];
i38 = i38 + 1;
}
}

}
void f_fourierTransform_802_calculateTransform(double *output, double *input, jint outputOffset, jint inputOffset, jint outputSize, jint inputSize, jint outputDim0, jint inputDim0, jint len, jint inverseTransform, jint isFirstSplit, jint global_id) {
double radix2_0[256];
double radix4Part1_1[128];
double radix4Part2_2[128];
double radix2FFT_3[256];
double radix4Part1FFT_4[128];
double radix4Part2FFT_5[128];
if (len >= 4) {
jint halfN_6 = len / 2;
jint quarterN_7 = len / 4;
jint tripleQuarterN_8 = quarterN_7 * 3;
double angle_9 = (inverseTransform <= 0) ? (- ((M_PI * 2.0) / len)) : ((M_PI * 2.0) / len);
double i_10 = (inverseTransform <= 0) ? 1 : -1;
for (int k11 = 0; k11 < quarterN_7;) {
jint kPlusTripleQuarterN_12 = k11 + tripleQuarterN_8;
jint kPlusHalfN_13 = k11 + halfN_6;
jint kPlusQuarterN_14 = k11 + quarterN_7;
radix2_0[k11 * 2] = input[(k11 * 2) + inputOffset] + input[(kPlusHalfN_13 * 2) + inputOffset];
radix2_0[(k11 * 2) + 1] = input[(k11 * 2) + inputOffset + 1] + input[(kPlusHalfN_13 * 2) + inputOffset + 1];
radix2_0[kPlusQuarterN_14 * 2] = input[(kPlusQuarterN_14 * 2) + inputOffset] + input[(kPlusTripleQuarterN_12 * 2) + inputOffset];
radix2_0[(kPlusQuarterN_14 * 2) + 1] = input[(kPlusQuarterN_14 * 2) + inputOffset + 1] + input[(kPlusTripleQuarterN_12 * 2) + inputOffset + 1];
double imaginaryTimesSubR_15 = - ((input[(kPlusQuarterN_14 * 2) + inputOffset + 1] - input[(kPlusTripleQuarterN_12 * 2) + inputOffset + 1]) * i_10);
double imaginaryTimesSubI_16 = (input[(kPlusQuarterN_14 * 2) + inputOffset] - input[(kPlusTripleQuarterN_12 * 2) + inputOffset]) * i_10;
double angleK_17 = angle_9 * k11;
double omegaR_18 = cos(angleK_17);
double omegaI_19 = sin(angleK_17);
double omegaToPowerOf3R_20 = cos(angleK_17 * 3.0);
double omegaToPowerOf3I_21 = sin(angleK_17 * 3.0);
radix4Part1_1[k11 * 2] = (((input[(k11 * 2) + inputOffset] - input[(kPlusHalfN_13 * 2) + inputOffset]) - imaginaryTimesSubR_15) * omegaR_18) - (((input[(k11 * 2) + inputOffset + 1] - input[(kPlusHalfN_13 * 2) + inputOffset + 1]) - imaginaryTimesSubI_16) * omegaI_19);
radix4Part1_1[(k11 * 2) + 1] = (((input[(k11 * 2) + inputOffset + 1] - input[(kPlusHalfN_13 * 2) + inputOffset + 1]) - imaginaryTimesSubI_16) * omegaR_18) + (((input[(k11 * 2) + inputOffset] - input[(kPlusHalfN_13 * 2) + inputOffset]) - imaginaryTimesSubR_15) * omegaI_19);
radix4Part2_2[k11 * 2] = (((input[(k11 * 2) + inputOffset] - input[(kPlusHalfN_13 * 2) + inputOffset]) + imaginaryTimesSubR_15) * omegaToPowerOf3R_20) - (((input[(k11 * 2) + inputOffset + 1] - input[(kPlusHalfN_13 * 2) + inputOffset + 1]) + imaginaryTimesSubI_16) * omegaToPowerOf3I_21);
radix4Part2_2[(k11 * 2) + 1] = (((input[(k11 * 2) + inputOffset + 1] - input[(kPlusHalfN_13 * 2) + inputOffset + 1]) + imaginaryTimesSubI_16) * omegaToPowerOf3R_20) + (((input[(k11 * 2) + inputOffset] - input[(kPlusHalfN_13 * 2) + inputOffset]) + imaginaryTimesSubR_15) * omegaToPowerOf3I_21);
k11 = k11 + 1;
}
f_fourierTransform_802_calculateTransform(radix2FFT_3, radix2_0, 0, 0, 256, 256, 0, 0, halfN_6, inverseTransform, 0, global_id);
f_fourierTransform_802_calculateTransform(radix4Part1FFT_4, radix4Part1_1, 0, 0, 128, 128, 0, 0, quarterN_7, inverseTransform, 0, global_id);
f_fourierTransform_802_calculateTransform(radix4Part2FFT_5, radix4Part2_2, 0, 0, 128, 128, 0, 0, quarterN_7, inverseTransform, 0, global_id);
for (int k = 0; k < quarterN_7;) {
jint doubleK_22 = k * 2;
jint quadrupleK_23 = doubleK_22 * 2;
if ((inverseTransform > 0) & (isFirstSplit > 0)) {
output[(doubleK_22 * 2) + outputOffset] = radix2FFT_3[doubleK_22] / len;
output[(doubleK_22 * 2) + outputOffset + 1] = radix2FFT_3[doubleK_22 + 1] / len;
output[((quadrupleK_23 + 1) * 2) + outputOffset] = radix4Part1FFT_4[doubleK_22] / len;
output[((quadrupleK_23 + 1) * 2) + outputOffset + 1] = radix4Part1FFT_4[doubleK_22 + 1] / len;
output[((doubleK_22 + halfN_6) * 2) + outputOffset] = radix2FFT_3[k + quarterN_7] / len;
output[((doubleK_22 + halfN_6) * 2) + outputOffset + 1] = radix2FFT_3[k + quarterN_7 + 1] / len;
output[((quadrupleK_23 + 3) * 2) + outputOffset] = radix4Part2FFT_5[doubleK_22] / len;
output[((quadrupleK_23 + 3) * 2) + outputOffset + 1] = radix4Part2FFT_5[doubleK_22 + 1] / len;
}
 else {
output[(doubleK_22 * 2) + outputOffset] = radix2FFT_3[doubleK_22];
output[(doubleK_22 * 2) + outputOffset + 1] = radix2FFT_3[doubleK_22 + 1];
output[((quadrupleK_23 + 1) * 2) + outputOffset] = radix4Part1FFT_4[doubleK_22];
output[((quadrupleK_23 + 1) * 2) + outputOffset + 1] = radix4Part1FFT_4[doubleK_22 + 1];
output[((doubleK_22 + halfN_6) * 2) + outputOffset] = radix2FFT_3[(k + quarterN_7) * 2];
output[((doubleK_22 + halfN_6) * 2) + outputOffset + 1] = radix2FFT_3[((k + quarterN_7) * 2) + 1];
output[((quadrupleK_23 + 3) * 2) + outputOffset] = radix4Part2FFT_5[doubleK_22];
output[((quadrupleK_23 + 3) * 2) + outputOffset + 1] = radix4Part2FFT_5[doubleK_22 + 1];
}
k = k + 1;
}
}
else if (len >= 2) {
f_fourierTransform_802_radix2(output, input, outputOffset, inputOffset, outputSize, inputSize, outputDim0, inputDim0, len, inverseTransform, isFirstSplit, global_id);
}
 else {
for (int i = 0; i < (len * 2);) {
output[i + outputOffset] = input[i + inputOffset];
i = i + 1;
}
}

}
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation48_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _802_v563Offset = (int) offsetArr[0];
jint _802_v564Offset = (int) offsetArr[1];
jint _802_v563Size = (int) sizeArr[0];
jint _802_v564Size = (int) sizeArr[1];
jint _802_v563Dim0 = (int) dim0Arr[0];
jint _802_v564Dim0 = (int) dim0Arr[1];
double *_802_v563 = ((double *) argArr[0]);
double *_802_v564 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
f_fourierTransform_802_calculateTransform(_802_v563, _802_v564, _802_v563Offset + 0, _802_v564Offset + 0 + 0, _802_v563Size, _802_v564Size, _802_v563Dim0, _802_v564Dim0, 256, 0, 0, global_id);

}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
