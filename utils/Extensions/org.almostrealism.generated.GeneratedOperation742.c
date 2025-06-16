#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation742_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11436_v7493Offset = (int) offsetArr[0];
jint _11436_v7494Offset = (int) offsetArr[1];
jint _11436_v7496Offset = (int) offsetArr[2];
jint _11436_v7493Size = (int) sizeArr[0];
jint _11436_v7494Size = (int) sizeArr[1];
jint _11436_v7496Size = (int) sizeArr[2];
jint _11436_v7493Dim0 = (int) dim0Arr[0];
jint _11436_v7494Dim0 = (int) dim0Arr[1];
jint _11436_v7496Dim0 = (int) dim0Arr[2];
double *_11436_v7493 = ((double *) argArr[0]);
double *_11436_v7494 = ((double *) argArr[1]);
double *_11436_v7496 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11436_v7493[global_id + _11436_v7493Offset] = _11436_v7494[global_id + _11436_v7494Offset + 90] * _11436_v7496[global_id + _11436_v7496Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
