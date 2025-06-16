#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation810_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12279_v8065Offset = (int) offsetArr[0];
jint _12274_v8062Offset = (int) offsetArr[1];
jint _12279_v8065Size = (int) sizeArr[0];
jint _12274_v8062Size = (int) sizeArr[1];
jint _12279_v8065Dim0 = (int) dim0Arr[0];
jint _12274_v8062Dim0 = (int) dim0Arr[1];
double *_12279_v8065 = ((double *) argArr[0]);
double *_12274_v8062 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12279_v8065[global_id + _12279_v8065Offset] = (_12274_v8062[global_id + _12274_v8062Offset + 40] + -0.04985448747383877) / 0.026043969471403416;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
