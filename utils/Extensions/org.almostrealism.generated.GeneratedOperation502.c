#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation502_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6666_v4972Offset = (int) offsetArr[0];
jint _6661_v4961Offset = (int) offsetArr[1];
jint _6666_v4972Size = (int) sizeArr[0];
jint _6661_v4961Size = (int) sizeArr[1];
jint _6666_v4972Dim0 = (int) dim0Arr[0];
jint _6661_v4961Dim0 = (int) dim0Arr[1];
double *_6666_v4972 = ((double *) argArr[0]);
double *_6661_v4961 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6666_v4972[global_id + _6666_v4972Offset] = ((- ((_6661_v4961[_6661_v4961Offset + 85] + _6661_v4961[_6661_v4961Offset + 86] + _6661_v4961[_6661_v4961Offset + 90] + _6661_v4961[_6661_v4961Offset + 87] + _6661_v4961[_6661_v4961Offset + 93] + _6661_v4961[_6661_v4961Offset + 89] + _6661_v4961[_6661_v4961Offset + 88] + _6661_v4961[_6661_v4961Offset + 95] + _6661_v4961[_6661_v4961Offset + 80] + _6661_v4961[_6661_v4961Offset + 81] + _6661_v4961[_6661_v4961Offset + 92] + _6661_v4961[_6661_v4961Offset + 82] + _6661_v4961[_6661_v4961Offset + 83] + _6661_v4961[_6661_v4961Offset + 84] + _6661_v4961[_6661_v4961Offset + 94] + _6661_v4961[_6661_v4961Offset + 91]) / 16.0)) + _6661_v4961[global_id + _6661_v4961Offset + 80]) * ((- ((_6661_v4961[_6661_v4961Offset + 85] + _6661_v4961[_6661_v4961Offset + 86] + _6661_v4961[_6661_v4961Offset + 90] + _6661_v4961[_6661_v4961Offset + 87] + _6661_v4961[_6661_v4961Offset + 93] + _6661_v4961[_6661_v4961Offset + 89] + _6661_v4961[_6661_v4961Offset + 88] + _6661_v4961[_6661_v4961Offset + 95] + _6661_v4961[_6661_v4961Offset + 80] + _6661_v4961[_6661_v4961Offset + 81] + _6661_v4961[_6661_v4961Offset + 92] + _6661_v4961[_6661_v4961Offset + 82] + _6661_v4961[_6661_v4961Offset + 83] + _6661_v4961[_6661_v4961Offset + 84] + _6661_v4961[_6661_v4961Offset + 94] + _6661_v4961[_6661_v4961Offset + 91]) / 16.0)) + _6661_v4961[global_id + _6661_v4961Offset + 80]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
