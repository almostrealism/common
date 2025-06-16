#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation289_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3492_v2875Offset = (int) offsetArr[0];
jint _3497_v2878Offset = (int) offsetArr[1];
jint _3500_v2883Offset = (int) offsetArr[2];
jint _3492_v2875Size = (int) sizeArr[0];
jint _3497_v2878Size = (int) sizeArr[1];
jint _3500_v2883Size = (int) sizeArr[2];
jint _3492_v2875Dim0 = (int) dim0Arr[0];
jint _3497_v2878Dim0 = (int) dim0Arr[1];
jint _3500_v2883Dim0 = (int) dim0Arr[2];
double *_3492_v2875 = ((double *) argArr[0]);
double *_3497_v2878 = ((double *) argArr[1]);
double *_3500_v2883 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3500_v2883[global_id + _3500_v2883Offset] = (- ((_3497_v2878[(global_id * 2) + _3497_v2878Offset + 1] + _3497_v2878[(global_id * 2) + _3497_v2878Offset]) * _3492_v2875[_3492_v2875Offset])) + _3500_v2883[global_id + _3500_v2883Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
