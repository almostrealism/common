#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation695_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10856_v6854Offset = (int) offsetArr[0];
jint _10880_v6857Offset = (int) offsetArr[1];
jint _10883_v6862Offset = (int) offsetArr[2];
jint _10856_v6854Size = (int) sizeArr[0];
jint _10880_v6857Size = (int) sizeArr[1];
jint _10883_v6862Size = (int) sizeArr[2];
jint _10856_v6854Dim0 = (int) dim0Arr[0];
jint _10880_v6857Dim0 = (int) dim0Arr[1];
jint _10883_v6862Dim0 = (int) dim0Arr[2];
double *_10856_v6854 = ((double *) argArr[0]);
double *_10880_v6857 = ((double *) argArr[1]);
double *_10883_v6862 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10883_v6862[global_id + _10883_v6862Offset] = (- ((_10880_v6857[(global_id * 2) + _10880_v6857Offset + 1] + _10880_v6857[(global_id * 2) + _10880_v6857Offset]) * _10856_v6854[_10856_v6854Offset])) + _10883_v6862[global_id + _10883_v6862Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
