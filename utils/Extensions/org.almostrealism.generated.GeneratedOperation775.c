#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation775_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11839_v7765Offset = (int) offsetArr[0];
jint _11839_v7766Offset = (int) offsetArr[1];
jint _11839_v7768Offset = (int) offsetArr[2];
jint _11839_v7765Size = (int) sizeArr[0];
jint _11839_v7766Size = (int) sizeArr[1];
jint _11839_v7768Size = (int) sizeArr[2];
jint _11839_v7765Dim0 = (int) dim0Arr[0];
jint _11839_v7766Dim0 = (int) dim0Arr[1];
jint _11839_v7768Dim0 = (int) dim0Arr[2];
double *_11839_v7765 = ((double *) argArr[0]);
double *_11839_v7766 = ((double *) argArr[1]);
double *_11839_v7768 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11839_v7765[global_id + _11839_v7765Offset] = _11839_v7766[global_id + _11839_v7766Offset + 50] * _11839_v7768[global_id + _11839_v7768Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
