#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation415_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5358_v4181Offset = (int) offsetArr[0];
jint _5356_v4179Offset = (int) offsetArr[1];
jint _5357_v4180Offset = (int) offsetArr[2];
jint _5358_v4181Size = (int) sizeArr[0];
jint _5356_v4179Size = (int) sizeArr[1];
jint _5357_v4180Size = (int) sizeArr[2];
jint _5358_v4181Dim0 = (int) dim0Arr[0];
jint _5356_v4179Dim0 = (int) dim0Arr[1];
jint _5357_v4180Dim0 = (int) dim0Arr[2];
double *_5358_v4181 = ((double *) argArr[0]);
double *_5356_v4179 = ((double *) argArr[1]);
double *_5357_v4180 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset] = _5356_v4179[(((global_id * 30) % 30) + (global_id * _5356_v4179Dim0)) + _5356_v4179Offset] * _5357_v4180[(((global_id * 30) % 30) + (global_id * _5357_v4180Dim0)) + _5357_v4180Offset];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 1] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 1] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 1];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 2] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 2] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 2];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 3] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 3] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 3];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 4] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 4] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 4];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 5] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 5] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 5];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 6] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 6] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 6];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 7] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 7] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 7];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 8] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 8] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 8];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 9] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 9] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 9];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 10] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 10] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 10];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 11] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 11] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 11];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 12] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 12] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 12];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 13] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 13] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 13];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 14] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 14] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 14];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 15] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 15] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 15];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 16] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 16] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 16];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 17] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 17] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 17];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 18] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 18] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 18];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 19] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 19] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 19];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 20] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 20] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 20];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 21] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 21] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 21];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 22] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 22] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 22];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 23] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 23] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 23];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 24] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 24] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 24];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 25] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 25] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 25];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 26] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 26] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 26];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 27] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 27] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 27];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 28] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 28] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 28];
_5358_v4181[(global_id * _5358_v4181Dim0) + _5358_v4181Offset + 29] = _5356_v4179[(global_id * _5356_v4179Dim0) + _5356_v4179Offset + 29] * _5357_v4180[(global_id * _5357_v4180Dim0) + _5357_v4180Offset + 29];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
