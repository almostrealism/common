#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation937_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14189_v9271Offset = (int) offsetArr[0];
jint _14184_v9260Offset = (int) offsetArr[1];
jint _14189_v9271Size = (int) sizeArr[0];
jint _14184_v9260Size = (int) sizeArr[1];
jint _14189_v9271Dim0 = (int) dim0Arr[0];
jint _14184_v9260Dim0 = (int) dim0Arr[1];
double *_14189_v9271 = ((double *) argArr[0]);
double *_14184_v9260 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14189_v9271[global_id + _14189_v9271Offset] = ((- ((_14184_v9260[_14184_v9260Offset] + _14184_v9260[_14184_v9260Offset + 1]) / 2.0)) + _14184_v9260[global_id + _14184_v9260Offset]) * ((- ((_14184_v9260[_14184_v9260Offset] + _14184_v9260[_14184_v9260Offset + 1]) / 2.0)) + _14184_v9260[global_id + _14184_v9260Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
