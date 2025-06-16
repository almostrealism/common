#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation645_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10045_v6600Offset = (int) offsetArr[0];
jint _10045_v6601Offset = (int) offsetArr[1];
jint _10045_v6600Size = (int) sizeArr[0];
jint _10045_v6601Size = (int) sizeArr[1];
jint _10045_v6600Dim0 = (int) dim0Arr[0];
jint _10045_v6601Dim0 = (int) dim0Arr[1];
double *_10045_v6600 = ((double *) argArr[0]);
double *_10045_v6601 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10045_v6600[global_id + _10045_v6600Offset] = _10045_v6601[(((global_id % 16) * 16) + (global_id / 16)) + _10045_v6601Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
