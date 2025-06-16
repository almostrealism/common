#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation487_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6465_v4833Offset = (int) offsetArr[0];
jint _6460_v4830Offset = (int) offsetArr[1];
jint _6465_v4833Size = (int) sizeArr[0];
jint _6460_v4830Size = (int) sizeArr[1];
jint _6465_v4833Dim0 = (int) dim0Arr[0];
jint _6460_v4830Dim0 = (int) dim0Arr[1];
double *_6465_v4833 = ((double *) argArr[0]);
double *_6460_v4830 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6465_v4833[global_id + _6465_v4833Offset] = (_6460_v4830[global_id + _6460_v4830Offset + 32] + -0.05551502411954125) / 0.02953342983279514;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
