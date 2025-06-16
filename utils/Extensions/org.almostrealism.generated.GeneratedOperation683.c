#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation683_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10796_v6997Offset = (int) offsetArr[0];
jint _10791_v6986Offset = (int) offsetArr[1];
jint _10796_v6997Size = (int) sizeArr[0];
jint _10791_v6986Size = (int) sizeArr[1];
jint _10796_v6997Dim0 = (int) dim0Arr[0];
jint _10791_v6986Dim0 = (int) dim0Arr[1];
double *_10796_v6997 = ((double *) argArr[0]);
double *_10791_v6986 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10796_v6997[global_id + _10796_v6997Offset] = ((- ((_10791_v6986[_10791_v6986Offset] + _10791_v6986[_10791_v6986Offset + 1]) / 2.0)) + _10791_v6986[global_id + _10791_v6986Offset]) * ((- ((_10791_v6986[_10791_v6986Offset] + _10791_v6986[_10791_v6986Offset + 1]) / 2.0)) + _10791_v6986[global_id + _10791_v6986Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
