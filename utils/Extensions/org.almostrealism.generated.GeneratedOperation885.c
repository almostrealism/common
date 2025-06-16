#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation885_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13376_v8758Offset = (int) offsetArr[0];
jint _13371_v8755Offset = (int) offsetArr[1];
jint _13376_v8758Size = (int) sizeArr[0];
jint _13371_v8755Size = (int) sizeArr[1];
jint _13376_v8758Dim0 = (int) dim0Arr[0];
jint _13371_v8755Dim0 = (int) dim0Arr[1];
double *_13376_v8758 = ((double *) argArr[0]);
double *_13371_v8755 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13376_v8758[global_id + _13376_v8758Offset] = (_13371_v8755[global_id + _13371_v8755Offset + 4] + -0.04210221104013393) / 0.013593504848301774;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
