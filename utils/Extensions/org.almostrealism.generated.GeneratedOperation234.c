#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation234_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2703_v2577Offset = (int) offsetArr[0];
jint _2704_v2580Offset = (int) offsetArr[1];
jint _2706_v2585Offset = (int) offsetArr[2];
jint _2703_v2577Size = (int) sizeArr[0];
jint _2704_v2580Size = (int) sizeArr[1];
jint _2706_v2585Size = (int) sizeArr[2];
jint _2703_v2577Dim0 = (int) dim0Arr[0];
jint _2704_v2580Dim0 = (int) dim0Arr[1];
jint _2706_v2585Dim0 = (int) dim0Arr[2];
double *_2703_v2577 = ((double *) argArr[0]);
double *_2704_v2580 = ((double *) argArr[1]);
double *_2706_v2585 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2706_v2585[global_id + _2706_v2585Offset] = (_2703_v2577[((global_id * 4) % 4) + _2703_v2577Offset] * _2704_v2580[(global_id * 4) + _2704_v2580Offset]) + (_2704_v2580[(global_id * 4) + _2704_v2580Offset + 1] * _2703_v2577[_2703_v2577Offset + 1]) + (_2704_v2580[(global_id * 4) + _2704_v2580Offset + 2] * _2703_v2577[_2703_v2577Offset + 2]) + (_2704_v2580[(global_id * 4) + _2704_v2580Offset + 3] * _2703_v2577[_2703_v2577Offset + 3]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
