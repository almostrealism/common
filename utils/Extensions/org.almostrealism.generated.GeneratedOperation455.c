#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation455_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6167_v4596Offset = (int) offsetArr[0];
jint _6167_v4597Offset = (int) offsetArr[1];
jint _6167_v4596Size = (int) sizeArr[0];
jint _6167_v4597Size = (int) sizeArr[1];
jint _6167_v4596Dim0 = (int) dim0Arr[0];
jint _6167_v4597Dim0 = (int) dim0Arr[1];
double *_6167_v4596 = ((double *) argArr[0]);
double *_6167_v4597 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6167_v4596[global_id + _6167_v4596Offset] = _6167_v4597[((((global_id % 1536) / 16) * 96) + ((global_id / 1536) * 16) + (global_id % 16)) + _6167_v4597Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
