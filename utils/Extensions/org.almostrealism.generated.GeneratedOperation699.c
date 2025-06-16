#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation699_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10940_v7142Offset = (int) offsetArr[0];
jint _10935_v7131Offset = (int) offsetArr[1];
jint _10940_v7142Size = (int) sizeArr[0];
jint _10935_v7131Size = (int) sizeArr[1];
jint _10940_v7142Dim0 = (int) dim0Arr[0];
jint _10935_v7131Dim0 = (int) dim0Arr[1];
double *_10940_v7142 = ((double *) argArr[0]);
double *_10935_v7131 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10940_v7142[global_id + _10940_v7142Offset] = ((- ((_10935_v7131[_10935_v7131Offset] + _10935_v7131[_10935_v7131Offset + 1]) / 2.0)) + _10935_v7131[global_id + _10935_v7131Offset]) * ((- ((_10935_v7131[_10935_v7131Offset] + _10935_v7131[_10935_v7131Offset + 1]) / 2.0)) + _10935_v7131[global_id + _10935_v7131Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
