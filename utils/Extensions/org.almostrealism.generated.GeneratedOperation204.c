#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation204_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2566_v2359Offset = (int) offsetArr[0];
jint _2566_v2360Offset = (int) offsetArr[1];
jint _2566_v2359Size = (int) sizeArr[0];
jint _2566_v2360Size = (int) sizeArr[1];
jint _2566_v2359Dim0 = (int) dim0Arr[0];
jint _2566_v2360Dim0 = (int) dim0Arr[1];
double *_2566_v2359 = ((double *) argArr[0]);
double *_2566_v2360 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset] = _2566_v2360[(((((global_id * 16) % 8) / 2) * 4) + ((((global_id * 16) % 16) / 8) * 2) + ((global_id * 16) % 2)) + _2566_v2360Offset];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 1] = _2566_v2360[_2566_v2360Offset + 1];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 2] = _2566_v2360[_2566_v2360Offset + 4];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 3] = _2566_v2360[_2566_v2360Offset + 5];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 4] = _2566_v2360[_2566_v2360Offset + 8];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 5] = _2566_v2360[_2566_v2360Offset + 9];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 6] = _2566_v2360[_2566_v2360Offset + 12];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 7] = _2566_v2360[_2566_v2360Offset + 13];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 8] = _2566_v2360[_2566_v2360Offset + 2];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 9] = _2566_v2360[_2566_v2360Offset + 3];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 10] = _2566_v2360[_2566_v2360Offset + 6];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 11] = _2566_v2360[_2566_v2360Offset + 7];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 12] = _2566_v2360[_2566_v2360Offset + 10];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 13] = _2566_v2360[_2566_v2360Offset + 11];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 14] = _2566_v2360[_2566_v2360Offset + 14];
_2566_v2359[(global_id * _2566_v2359Dim0) + _2566_v2359Offset + 15] = _2566_v2360[_2566_v2360Offset + 15];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
