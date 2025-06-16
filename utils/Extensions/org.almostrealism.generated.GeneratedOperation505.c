#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation505_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6705_v4998Offset = (int) offsetArr[0];
jint _6700_v4995Offset = (int) offsetArr[1];
jint _6705_v4998Size = (int) sizeArr[0];
jint _6700_v4995Size = (int) sizeArr[1];
jint _6705_v4998Dim0 = (int) dim0Arr[0];
jint _6700_v4995Dim0 = (int) dim0Arr[1];
double *_6705_v4998 = ((double *) argArr[0]);
double *_6700_v4995 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6705_v4998[global_id + _6705_v4998Offset] = (_6700_v4995[global_id + _6700_v4995Offset + 80] + -0.03732065256628082) / 0.028628294837052082;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
