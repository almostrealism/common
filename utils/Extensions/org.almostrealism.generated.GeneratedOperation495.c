#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation495_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6554_v4895Offset = (int) offsetArr[0];
jint _6554_v4896Offset = (int) offsetArr[1];
jint _6554_v4898Offset = (int) offsetArr[2];
jint _6554_v4895Size = (int) sizeArr[0];
jint _6554_v4896Size = (int) sizeArr[1];
jint _6554_v4898Size = (int) sizeArr[2];
jint _6554_v4895Dim0 = (int) dim0Arr[0];
jint _6554_v4896Dim0 = (int) dim0Arr[1];
jint _6554_v4898Dim0 = (int) dim0Arr[2];
double *_6554_v4895 = ((double *) argArr[0]);
double *_6554_v4896 = ((double *) argArr[1]);
double *_6554_v4898 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6554_v4895[global_id + _6554_v4895Offset] = _6554_v4896[global_id + _6554_v4896Offset] * _6554_v4898[global_id + _6554_v4898Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
