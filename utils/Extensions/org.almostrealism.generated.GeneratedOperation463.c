#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation463_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6205_v4601Offset = (int) offsetArr[0];
jint _6205_v4602Offset = (int) offsetArr[1];
jint _6205_v4601Size = (int) sizeArr[0];
jint _6205_v4602Size = (int) sizeArr[1];
jint _6205_v4601Dim0 = (int) dim0Arr[0];
jint _6205_v4602Dim0 = (int) dim0Arr[1];
double *_6205_v4601 = ((double *) argArr[0]);
double *_6205_v4602 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6205_v4601[global_id + _6205_v4601Offset] = _6205_v4602[(((global_id % 96) * 96) + (global_id / 96)) + _6205_v4602Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
