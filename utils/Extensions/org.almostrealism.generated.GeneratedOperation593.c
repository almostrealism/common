#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation593_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9159_v6035Offset = (int) offsetArr[0];
jint _9159_v6036Offset = (int) offsetArr[1];
jint _9159_v6035Size = (int) sizeArr[0];
jint _9159_v6036Size = (int) sizeArr[1];
jint _9159_v6035Dim0 = (int) dim0Arr[0];
jint _9159_v6036Dim0 = (int) dim0Arr[1];
double *_9159_v6035 = ((double *) argArr[0]);
double *_9159_v6036 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9159_v6035[global_id + _9159_v6035Offset] = _9159_v6036[(((global_id % 8) * 8) + (global_id / 8)) + _9159_v6036Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
