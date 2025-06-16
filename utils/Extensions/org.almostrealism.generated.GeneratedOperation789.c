#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation789_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12124_v7964Offset = (int) offsetArr[0];
jint _12090_v7924Offset = (int) offsetArr[1];
jint _12093_v7929Offset = (int) offsetArr[2];
jint _12110_v7937Offset = (int) offsetArr[3];
jint _12119_v7953Offset = (int) offsetArr[4];
jint _12124_v7964Size = (int) sizeArr[0];
jint _12090_v7924Size = (int) sizeArr[1];
jint _12093_v7929Size = (int) sizeArr[2];
jint _12110_v7937Size = (int) sizeArr[3];
jint _12119_v7953Size = (int) sizeArr[4];
jint _12124_v7964Dim0 = (int) dim0Arr[0];
jint _12090_v7924Dim0 = (int) dim0Arr[1];
jint _12093_v7929Dim0 = (int) dim0Arr[2];
jint _12110_v7937Dim0 = (int) dim0Arr[3];
jint _12119_v7953Dim0 = (int) dim0Arr[4];
double *_12124_v7964 = ((double *) argArr[0]);
double *_12090_v7924 = ((double *) argArr[1]);
double *_12093_v7929 = ((double *) argArr[2]);
double *_12110_v7937 = ((double *) argArr[3]);
double *_12119_v7953 = ((double *) argArr[4]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12124_v7964[global_id + _12124_v7964Offset] = ((((((- (global_id % 80)) + (global_id / 80)) == 0) ? 1 : 0) + (_12110_v7937[(((global_id / 1600) * 80) + (global_id % 80)) + _12110_v7937Offset] * -0.05)) * ((- (_12090_v7924[(global_id / 1600) + _12090_v7924Offset] / 20.0)) + _12093_v7929[(global_id / 80) + _12093_v7929Offset])) + ((((((- (global_id % 80)) + (global_id / 80)) == 0) ? 1 : 0) + (_12119_v7953[(((global_id / 1600) * 80) + (global_id % 80)) + _12119_v7953Offset] * -0.05)) * ((- (_12090_v7924[(global_id / 1600) + _12090_v7924Offset] / 20.0)) + _12093_v7929[(global_id / 80) + _12093_v7929Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
