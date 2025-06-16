#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation861_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13209_v8644Offset = (int) offsetArr[0];
jint _13174_v8597Offset = (int) offsetArr[1];
jint _13194_v8613Offset = (int) offsetArr[2];
jint _13203_v8631Offset = (int) offsetArr[3];
jint _13209_v8644Size = (int) sizeArr[0];
jint _13174_v8597Size = (int) sizeArr[1];
jint _13194_v8613Size = (int) sizeArr[2];
jint _13203_v8631Size = (int) sizeArr[3];
jint _13209_v8644Dim0 = (int) dim0Arr[0];
jint _13174_v8597Dim0 = (int) dim0Arr[1];
jint _13194_v8613Dim0 = (int) dim0Arr[2];
jint _13203_v8631Dim0 = (int) dim0Arr[3];
double *_13209_v8644 = ((double *) argArr[0]);
double *_13174_v8597 = ((double *) argArr[1]);
double *_13194_v8613 = ((double *) argArr[2]);
double *_13203_v8631 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13209_v8644[global_id + _13209_v8644Offset] = ((((_13194_v8613[((((global_id / 16) * 8) + (global_id % 8)) * 2) + _13194_v8613Offset + 1] + _13194_v8613[((((global_id / 16) * 8) + (global_id % 8)) * 2) + _13194_v8613Offset]) * -0.5) + ((((- (global_id % 8)) + (global_id / 8)) == 0) ? 1 : 0)) * ((- ((_13174_v8597[((global_id / 16) * 2) + _13174_v8597Offset + 1] + _13174_v8597[((global_id / 16) * 2) + _13174_v8597Offset]) / 2.0)) + _13174_v8597[(global_id / 8) + _13174_v8597Offset])) + ((((_13203_v8631[((((global_id / 16) * 8) + (global_id % 8)) * 2) + _13203_v8631Offset + 1] + _13203_v8631[((((global_id / 16) * 8) + (global_id % 8)) * 2) + _13203_v8631Offset]) * -0.5) + ((((- (global_id % 8)) + (global_id / 8)) == 0) ? 1 : 0)) * ((- ((_13174_v8597[((global_id / 16) * 2) + _13174_v8597Offset + 1] + _13174_v8597[((global_id / 16) * 2) + _13174_v8597Offset]) / 2.0)) + _13174_v8597[(global_id / 8) + _13174_v8597Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
