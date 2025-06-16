#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation774_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11831_v7763Offset = (int) offsetArr[0];
jint _11826_v7760Offset = (int) offsetArr[1];
jint _11831_v7763Size = (int) sizeArr[0];
jint _11826_v7760Size = (int) sizeArr[1];
jint _11831_v7763Dim0 = (int) dim0Arr[0];
jint _11826_v7760Dim0 = (int) dim0Arr[1];
double *_11831_v7763 = ((double *) argArr[0]);
double *_11826_v7760 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11831_v7763[global_id + _11831_v7763Offset] = (_11826_v7760[global_id + _11826_v7760Offset + 50] + -0.042775579131332536) / 0.02905361102383334;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
