#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation832_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12587_v8281Offset = (int) offsetArr[0];
jint _12587_v8282Offset = (int) offsetArr[1];
jint _12587_v8281Size = (int) sizeArr[0];
jint _12587_v8282Size = (int) sizeArr[1];
jint _12587_v8281Dim0 = (int) dim0Arr[0];
jint _12587_v8282Dim0 = (int) dim0Arr[1];
double *_12587_v8281 = ((double *) argArr[0]);
double *_12587_v8282 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12587_v8281[global_id + _12587_v8281Offset] = _12587_v8282[((((global_id % 3600) / 30) * 120) + ((global_id / 3600) * 30) + (global_id % 30)) + _12587_v8282Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
