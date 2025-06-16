#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation362_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4699_v3773Offset = (int) offsetArr[0];
jint _4694_v3762Offset = (int) offsetArr[1];
jint _4699_v3773Size = (int) sizeArr[0];
jint _4694_v3762Size = (int) sizeArr[1];
jint _4699_v3773Dim0 = (int) dim0Arr[0];
jint _4694_v3762Dim0 = (int) dim0Arr[1];
double *_4699_v3773 = ((double *) argArr[0]);
double *_4694_v3762 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4699_v3773[global_id + _4699_v3773Offset] = ((- ((_4694_v3762[_4694_v3762Offset] + _4694_v3762[_4694_v3762Offset + 1]) / 2.0)) + _4694_v3762[global_id + _4694_v3762Offset]) * ((- ((_4694_v3762[_4694_v3762Offset] + _4694_v3762[_4694_v3762Offset + 1]) / 2.0)) + _4694_v3762[global_id + _4694_v3762Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
