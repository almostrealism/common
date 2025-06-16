#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation275_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3463_v3054Offset = (int) offsetArr[0];
jint _3428_v3007Offset = (int) offsetArr[1];
jint _3448_v3023Offset = (int) offsetArr[2];
jint _3457_v3041Offset = (int) offsetArr[3];
jint _3463_v3054Size = (int) sizeArr[0];
jint _3428_v3007Size = (int) sizeArr[1];
jint _3448_v3023Size = (int) sizeArr[2];
jint _3457_v3041Size = (int) sizeArr[3];
jint _3463_v3054Dim0 = (int) dim0Arr[0];
jint _3428_v3007Dim0 = (int) dim0Arr[1];
jint _3448_v3023Dim0 = (int) dim0Arr[2];
jint _3457_v3041Dim0 = (int) dim0Arr[3];
double *_3463_v3054 = ((double *) argArr[0]);
double *_3428_v3007 = ((double *) argArr[1]);
double *_3448_v3023 = ((double *) argArr[2]);
double *_3457_v3041 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3463_v3054[global_id + _3463_v3054Offset] = ((((_3448_v3023[((global_id % 2) * 2) + _3448_v3023Offset + 1] + _3448_v3023[((global_id % 2) * 2) + _3448_v3023Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * ((- ((_3428_v3007[_3428_v3007Offset] + _3428_v3007[_3428_v3007Offset + 1]) / 2.0)) + _3428_v3007[(global_id / 2) + _3428_v3007Offset])) + ((((_3457_v3041[((global_id % 2) * 2) + _3457_v3041Offset + 1] + _3457_v3041[((global_id % 2) * 2) + _3457_v3041Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * ((- ((_3428_v3007[_3428_v3007Offset] + _3428_v3007[_3428_v3007Offset + 1]) / 2.0)) + _3428_v3007[(global_id / 2) + _3428_v3007Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
