#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation550_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7781_v5501Offset = (int) offsetArr[0];
jint _7718_v5396Offset = (int) offsetArr[1];
jint _7719_v5399Offset = (int) offsetArr[2];
jint _7761_v5452Offset = (int) offsetArr[3];
jint _7771_v5476Offset = (int) offsetArr[4];
jint _7779_v5496Offset = (int) offsetArr[5];
jint _7781_v5501Size = (int) sizeArr[0];
jint _7718_v5396Size = (int) sizeArr[1];
jint _7719_v5399Size = (int) sizeArr[2];
jint _7761_v5452Size = (int) sizeArr[3];
jint _7771_v5476Size = (int) sizeArr[4];
jint _7779_v5496Size = (int) sizeArr[5];
jint _7781_v5501Dim0 = (int) dim0Arr[0];
jint _7718_v5396Dim0 = (int) dim0Arr[1];
jint _7719_v5399Dim0 = (int) dim0Arr[2];
jint _7761_v5452Dim0 = (int) dim0Arr[3];
jint _7771_v5476Dim0 = (int) dim0Arr[4];
jint _7779_v5496Dim0 = (int) dim0Arr[5];
double *_7781_v5501 = ((double *) argArr[0]);
double *_7718_v5396 = ((double *) argArr[1]);
double *_7719_v5399 = ((double *) argArr[2]);
double *_7761_v5452 = ((double *) argArr[3]);
double *_7771_v5476 = ((double *) argArr[4]);
double *_7779_v5496 = ((double *) argArr[5]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7781_v5501[global_id + _7781_v5501Offset] = (((((- pow(pow((((((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset]) * ((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset])) + (((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset + 1]) * ((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset + 1])) + (((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset + 2]) * ((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset + 2]))) / 3.0) + 1.0E-5, 0.5), -2.0)) * ((pow((((((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset]) * ((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset])) + (((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset + 1]) * ((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset + 1])) + (((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset + 2]) * ((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset + 2]))) / 3.0) + 1.0E-5, -0.5) * 0.5) * ((_7761_v5452[((global_id / 3) * 3) + _7761_v5452Offset + 1] + _7761_v5452[((global_id / 3) * 3) + _7761_v5452Offset + 2] + _7761_v5452[((global_id / 3) * 3) + _7761_v5452Offset]) * 0.3333333333333333))) * ((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[(global_id % 3) + _7719_v5399Offset])) + (pow(pow((((((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset]) * ((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset])) + (((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset + 1]) * ((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset + 1])) + (((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset + 2]) * ((- ((_7719_v5399[_7719_v5399Offset] + _7719_v5399[_7719_v5399Offset + 1] + _7719_v5399[_7719_v5399Offset + 2]) / 3.0)) + _7719_v5399[_7719_v5399Offset + 2]))) / 3.0) + 1.0E-5, 0.5), -1.0) * (((((- (((global_id % 3) * 3) + (global_id / 3))) + ((global_id % 3) * 4)) == 0) ? 1 : 0) + ((_7771_v5476[((global_id / 3) * 3) + _7771_v5476Offset + 1] + _7771_v5476[((global_id / 3) * 3) + _7771_v5476Offset + 2] + _7771_v5476[((global_id / 3) * 3) + _7771_v5476Offset]) * -0.3333333333333333)))) * _7718_v5396[(global_id % 3) + _7718_v5396Offset]) * _7779_v5496[(global_id % 3) + _7779_v5496Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
