#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation215_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2634_v2424Offset = (int) offsetArr[0];
jint _2635_v2427Offset = (int) offsetArr[1];
jint _2637_v2432Offset = (int) offsetArr[2];
jint _2634_v2424Size = (int) sizeArr[0];
jint _2635_v2427Size = (int) sizeArr[1];
jint _2637_v2432Size = (int) sizeArr[2];
jint _2634_v2424Dim0 = (int) dim0Arr[0];
jint _2635_v2427Dim0 = (int) dim0Arr[1];
jint _2637_v2432Dim0 = (int) dim0Arr[2];
double *_2634_v2424 = ((double *) argArr[0]);
double *_2635_v2427 = ((double *) argArr[1]);
double *_2637_v2432 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2637_v2432[global_id + _2637_v2432Offset] = (_2634_v2424[((global_id * 4) % 4) + _2634_v2424Offset] * _2635_v2427[(global_id * 4) + _2635_v2427Offset]) + (_2635_v2427[(global_id * 4) + _2635_v2427Offset + 1] * _2634_v2424[_2634_v2424Offset + 1]) + (_2635_v2427[(global_id * 4) + _2635_v2427Offset + 2] * _2634_v2424[_2634_v2424Offset + 2]) + (_2635_v2427[(global_id * 4) + _2635_v2427Offset + 3] * _2634_v2424[_2634_v2424Offset + 3]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
