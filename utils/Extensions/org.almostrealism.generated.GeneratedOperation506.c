#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation506_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6713_v5000Offset = (int) offsetArr[0];
jint _6713_v5001Offset = (int) offsetArr[1];
jint _6713_v5003Offset = (int) offsetArr[2];
jint _6713_v5000Size = (int) sizeArr[0];
jint _6713_v5001Size = (int) sizeArr[1];
jint _6713_v5003Size = (int) sizeArr[2];
jint _6713_v5000Dim0 = (int) dim0Arr[0];
jint _6713_v5001Dim0 = (int) dim0Arr[1];
jint _6713_v5003Dim0 = (int) dim0Arr[2];
double *_6713_v5000 = ((double *) argArr[0]);
double *_6713_v5001 = ((double *) argArr[1]);
double *_6713_v5003 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6713_v5000[global_id + _6713_v5000Offset] = _6713_v5001[global_id + _6713_v5001Offset + 80] * _6713_v5003[global_id + _6713_v5003Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
