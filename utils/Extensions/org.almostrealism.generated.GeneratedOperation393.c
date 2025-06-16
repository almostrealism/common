#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation393_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5297_v3958Offset = (int) offsetArr[0];
jint _5248_v3933Offset = (int) offsetArr[1];
jint _5293_v3950Offset = (int) offsetArr[2];
jint _5297_v3958Size = (int) sizeArr[0];
jint _5248_v3933Size = (int) sizeArr[1];
jint _5293_v3950Size = (int) sizeArr[2];
jint _5297_v3958Dim0 = (int) dim0Arr[0];
jint _5248_v3933Dim0 = (int) dim0Arr[1];
jint _5293_v3950Dim0 = (int) dim0Arr[2];
double *_5297_v3958 = ((double *) argArr[0]);
double *_5248_v3933 = ((double *) argArr[1]);
double *_5293_v3950 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5297_v3958[global_id + _5297_v3958Offset] = pow(pow((_5248_v3933[(global_id / 3600) + _5248_v3933Offset] / 30.0) + 1.0E-5, 0.5), -1.0) * (((((- (global_id % 120)) + (global_id / 120)) == 0) ? 1 : 0) + (_5293_v3950[(((global_id / 3600) * 120) + (global_id % 120)) + _5293_v3950Offset] * -0.03333333333333333));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
