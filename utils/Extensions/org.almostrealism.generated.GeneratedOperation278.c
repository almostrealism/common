#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation278_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3433_v2999Offset = (int) offsetArr[0];
jint _3428_v2988Offset = (int) offsetArr[1];
jint _3433_v2999Size = (int) sizeArr[0];
jint _3428_v2988Size = (int) sizeArr[1];
jint _3433_v2999Dim0 = (int) dim0Arr[0];
jint _3428_v2988Dim0 = (int) dim0Arr[1];
double *_3433_v2999 = ((double *) argArr[0]);
double *_3428_v2988 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3433_v2999[global_id + _3433_v2999Offset] = ((- ((_3428_v2988[_3428_v2988Offset] + _3428_v2988[_3428_v2988Offset + 1]) / 2.0)) + _3428_v2988[global_id + _3428_v2988Offset]) * ((- ((_3428_v2988[_3428_v2988Offset] + _3428_v2988[_3428_v2988Offset + 1]) / 2.0)) + _3428_v2988[global_id + _3428_v2988Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
