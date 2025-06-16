#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation264_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3138_v2860Offset = (int) offsetArr[0];
jint _3134_v2852Offset = (int) offsetArr[1];
jint _3137_v2857Offset = (int) offsetArr[2];
jint _3138_v2860Size = (int) sizeArr[0];
jint _3134_v2852Size = (int) sizeArr[1];
jint _3137_v2857Size = (int) sizeArr[2];
jint _3138_v2860Dim0 = (int) dim0Arr[0];
jint _3134_v2852Dim0 = (int) dim0Arr[1];
jint _3137_v2857Dim0 = (int) dim0Arr[2];
double *_3138_v2860 = ((double *) argArr[0]);
double *_3134_v2852 = ((double *) argArr[1]);
double *_3137_v2857 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3138_v2860[global_id + _3138_v2860Offset] = ((- (_3134_v2852[(global_id / 30) + _3134_v2852Offset] / 30.0)) + _3137_v2857[global_id + _3137_v2857Offset]) * ((- (_3134_v2852[(global_id / 30) + _3134_v2852Offset] / 30.0)) + _3137_v2857[global_id + _3137_v2857Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
