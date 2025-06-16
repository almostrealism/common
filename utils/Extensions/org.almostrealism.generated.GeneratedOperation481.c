#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation481_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6385_v4778Offset = (int) offsetArr[0];
jint _6380_v4775Offset = (int) offsetArr[1];
jint _6385_v4778Size = (int) sizeArr[0];
jint _6380_v4775Size = (int) sizeArr[1];
jint _6385_v4778Dim0 = (int) dim0Arr[0];
jint _6380_v4775Dim0 = (int) dim0Arr[1];
double *_6385_v4778 = ((double *) argArr[0]);
double *_6380_v4775 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6385_v4778[global_id + _6385_v4778Offset] = (_6380_v4775[global_id + _6380_v4775Offset + 16] + -0.0471188929378534) / 0.017641010844505867;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
