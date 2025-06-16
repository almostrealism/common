#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation203_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2565_v2361Offset = (int) offsetArr[0];
jint _2565_v2362Offset = (int) offsetArr[1];
jint _2565_v2361Size = (int) sizeArr[0];
jint _2565_v2362Size = (int) sizeArr[1];
jint _2565_v2361Dim0 = (int) dim0Arr[0];
jint _2565_v2362Dim0 = (int) dim0Arr[1];
double *_2565_v2361 = ((double *) argArr[0]);
double *_2565_v2362 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset] = _2565_v2362[((((global_id * 16) % 4) * 4) + (((global_id * 16) % 16) / 4)) + _2565_v2362Offset];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 1] = _2565_v2362[_2565_v2362Offset + 4];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 2] = _2565_v2362[_2565_v2362Offset + 8];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 3] = _2565_v2362[_2565_v2362Offset + 12];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 4] = _2565_v2362[_2565_v2362Offset + 1];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 5] = _2565_v2362[_2565_v2362Offset + 5];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 6] = _2565_v2362[_2565_v2362Offset + 9];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 7] = _2565_v2362[_2565_v2362Offset + 13];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 8] = _2565_v2362[_2565_v2362Offset + 2];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 9] = _2565_v2362[_2565_v2362Offset + 6];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 10] = _2565_v2362[_2565_v2362Offset + 10];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 11] = _2565_v2362[_2565_v2362Offset + 14];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 12] = _2565_v2362[_2565_v2362Offset + 3];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 13] = _2565_v2362[_2565_v2362Offset + 7];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 14] = _2565_v2362[_2565_v2362Offset + 11];
_2565_v2361[(global_id * _2565_v2361Dim0) + _2565_v2361Offset + 15] = _2565_v2362[_2565_v2362Offset + 15];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
