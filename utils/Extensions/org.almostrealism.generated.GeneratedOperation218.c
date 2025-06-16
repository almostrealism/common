#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation218_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2646_v2457Offset = (int) offsetArr[0];
jint _2647_v2460Offset = (int) offsetArr[1];
jint _2649_v2465Offset = (int) offsetArr[2];
jint _2646_v2457Size = (int) sizeArr[0];
jint _2647_v2460Size = (int) sizeArr[1];
jint _2649_v2465Size = (int) sizeArr[2];
jint _2646_v2457Dim0 = (int) dim0Arr[0];
jint _2647_v2460Dim0 = (int) dim0Arr[1];
jint _2649_v2465Dim0 = (int) dim0Arr[2];
double *_2646_v2457 = ((double *) argArr[0]);
double *_2647_v2460 = ((double *) argArr[1]);
double *_2649_v2465 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2649_v2465[global_id + _2649_v2465Offset] = (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 3] * _2646_v2457[_2646_v2457Offset + 3]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 4] * _2646_v2457[_2646_v2457Offset + 4]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 20] * _2646_v2457[_2646_v2457Offset + 20]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 14] * _2646_v2457[_2646_v2457Offset + 14]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 11] * _2646_v2457[_2646_v2457Offset + 11]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 29] * _2646_v2457[_2646_v2457Offset + 29]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 18] * _2646_v2457[_2646_v2457Offset + 18]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 23] * _2646_v2457[_2646_v2457Offset + 23]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 26] * _2646_v2457[_2646_v2457Offset + 26]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 16] * _2646_v2457[_2646_v2457Offset + 16]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 5] * _2646_v2457[_2646_v2457Offset + 5]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 6] * _2646_v2457[_2646_v2457Offset + 6]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 10] * _2646_v2457[_2646_v2457Offset + 10]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 7] * _2646_v2457[_2646_v2457Offset + 7]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 30] * _2646_v2457[_2646_v2457Offset + 30]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 21] * _2646_v2457[_2646_v2457Offset + 21]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 13] * _2646_v2457[_2646_v2457Offset + 13]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 9] * _2646_v2457[_2646_v2457Offset + 9]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 27] * _2646_v2457[_2646_v2457Offset + 27]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 24] * _2646_v2457[_2646_v2457Offset + 24]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 8] * _2646_v2457[_2646_v2457Offset + 8]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 19] * _2646_v2457[_2646_v2457Offset + 19]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 15] * _2646_v2457[_2646_v2457Offset + 15]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 31] * _2646_v2457[_2646_v2457Offset + 31]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 17] * _2646_v2457[_2646_v2457Offset + 17]) + (_2646_v2457[((global_id * 32) % 32) + _2646_v2457Offset] * _2647_v2460[(global_id * 32) + _2647_v2460Offset]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 1] * _2646_v2457[_2646_v2457Offset + 1]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 22] * _2646_v2457[_2646_v2457Offset + 22]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 28] * _2646_v2457[_2646_v2457Offset + 28]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 12] * _2646_v2457[_2646_v2457Offset + 12]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 25] * _2646_v2457[_2646_v2457Offset + 25]) + (_2647_v2460[(global_id * 32) + _2647_v2460Offset + 2] * _2646_v2457[_2646_v2457Offset + 2]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
