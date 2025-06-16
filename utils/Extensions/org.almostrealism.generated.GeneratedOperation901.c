#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation901_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13781_v8850Offset = (int) offsetArr[0];
jint _13730_v8835Offset = (int) offsetArr[1];
jint _13780_v8849Offset = (int) offsetArr[2];
jint _13781_v8850Size = (int) sizeArr[0];
jint _13730_v8835Size = (int) sizeArr[1];
jint _13780_v8849Size = (int) sizeArr[2];
jint _13781_v8850Dim0 = (int) dim0Arr[0];
jint _13730_v8835Dim0 = (int) dim0Arr[1];
jint _13780_v8849Dim0 = (int) dim0Arr[2];
double *_13781_v8850 = ((double *) argArr[0]);
double *_13730_v8835 = ((double *) argArr[1]);
double *_13780_v8849 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13781_v8850[global_id + _13781_v8850Offset] = ((- ((_13730_v8835[((global_id / 64) * 4) + _13730_v8835Offset + 1] + _13730_v8835[((global_id / 64) * 4) + _13730_v8835Offset + 2] + _13730_v8835[((global_id / 64) * 4) + _13730_v8835Offset + 3] + _13730_v8835[((global_id / 64) * 4) + _13730_v8835Offset]) / 4.0)) + _13730_v8835[(global_id / 16) + _13730_v8835Offset]) * _13780_v8849[(((global_id / 64) * 16) + (global_id % 16)) + _13780_v8849Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
