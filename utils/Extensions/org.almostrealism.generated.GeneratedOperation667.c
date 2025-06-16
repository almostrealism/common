#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation667_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10336_v6807Offset = (int) offsetArr[0];
jint _10331_v6796Offset = (int) offsetArr[1];
jint _10336_v6807Size = (int) sizeArr[0];
jint _10331_v6796Size = (int) sizeArr[1];
jint _10336_v6807Dim0 = (int) dim0Arr[0];
jint _10331_v6796Dim0 = (int) dim0Arr[1];
double *_10336_v6807 = ((double *) argArr[0]);
double *_10331_v6796 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10336_v6807[global_id + _10336_v6807Offset] = ((- ((_10331_v6796[_10331_v6796Offset + 12] + _10331_v6796[_10331_v6796Offset + 13] + _10331_v6796[_10331_v6796Offset + 14] + _10331_v6796[_10331_v6796Offset + 15]) / 4.0)) + _10331_v6796[global_id + _10331_v6796Offset + 12]) * ((- ((_10331_v6796[_10331_v6796Offset + 12] + _10331_v6796[_10331_v6796Offset + 13] + _10331_v6796[_10331_v6796Offset + 14] + _10331_v6796[_10331_v6796Offset + 15]) / 4.0)) + _10331_v6796[global_id + _10331_v6796Offset + 12]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
