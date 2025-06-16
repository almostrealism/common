#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation781_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12094_v7912Offset = (int) offsetArr[0];
jint _12090_v7904Offset = (int) offsetArr[1];
jint _12093_v7909Offset = (int) offsetArr[2];
jint _12094_v7912Size = (int) sizeArr[0];
jint _12090_v7904Size = (int) sizeArr[1];
jint _12093_v7909Size = (int) sizeArr[2];
jint _12094_v7912Dim0 = (int) dim0Arr[0];
jint _12090_v7904Dim0 = (int) dim0Arr[1];
jint _12093_v7909Dim0 = (int) dim0Arr[2];
double *_12094_v7912 = ((double *) argArr[0]);
double *_12090_v7904 = ((double *) argArr[1]);
double *_12093_v7909 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12094_v7912[global_id + _12094_v7912Offset] = ((- (_12090_v7904[(global_id / 20) + _12090_v7904Offset] / 20.0)) + _12093_v7909[global_id + _12093_v7909Offset]) * ((- (_12090_v7904[(global_id / 20) + _12090_v7904Offset] / 20.0)) + _12093_v7909[global_id + _12093_v7909Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
