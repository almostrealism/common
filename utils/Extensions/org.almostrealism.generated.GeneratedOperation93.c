#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation93_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1168_v822Offset = (int) offsetArr[0];
jint _1168_v823Offset = (int) offsetArr[1];
jint _1168_v825Offset = (int) offsetArr[2];
jint _1168_v822Size = (int) sizeArr[0];
jint _1168_v823Size = (int) sizeArr[1];
jint _1168_v825Size = (int) sizeArr[2];
jint _1168_v822Dim0 = (int) dim0Arr[0];
jint _1168_v823Dim0 = (int) dim0Arr[1];
jint _1168_v825Dim0 = (int) dim0Arr[2];
double *_1168_v822 = ((double *) argArr[0]);
double *_1168_v823 = ((double *) argArr[1]);
double *_1168_v825 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1168_v822[global_id + _1168_v822Offset] = _1168_v823[(global_id / 2) + _1168_v823Offset] * _1168_v825[(global_id % 2) + _1168_v825Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
