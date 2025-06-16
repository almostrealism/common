#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation923_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13967_v9132Offset = (int) offsetArr[0];
jint _13948_v9117Offset = (int) offsetArr[1];
jint _13967_v9132Size = (int) sizeArr[0];
jint _13948_v9117Size = (int) sizeArr[1];
jint _13967_v9132Dim0 = (int) dim0Arr[0];
jint _13948_v9117Dim0 = (int) dim0Arr[1];
double *_13967_v9132 = ((double *) argArr[0]);
double *_13948_v9117 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13967_v9132[_13967_v9132Offset] = ((((- ((_13948_v9117[_13948_v9117Offset + 12] + _13948_v9117[_13948_v9117Offset + 13] + _13948_v9117[_13948_v9117Offset + 14] + _13948_v9117[_13948_v9117Offset + 15]) / 4.0)) + _13948_v9117[_13948_v9117Offset + 12]) * ((- ((_13948_v9117[_13948_v9117Offset + 12] + _13948_v9117[_13948_v9117Offset + 13] + _13948_v9117[_13948_v9117Offset + 14] + _13948_v9117[_13948_v9117Offset + 15]) / 4.0)) + _13948_v9117[_13948_v9117Offset + 12])) + (((- ((_13948_v9117[_13948_v9117Offset + 12] + _13948_v9117[_13948_v9117Offset + 13] + _13948_v9117[_13948_v9117Offset + 14] + _13948_v9117[_13948_v9117Offset + 15]) / 4.0)) + _13948_v9117[_13948_v9117Offset + 13]) * ((- ((_13948_v9117[_13948_v9117Offset + 12] + _13948_v9117[_13948_v9117Offset + 13] + _13948_v9117[_13948_v9117Offset + 14] + _13948_v9117[_13948_v9117Offset + 15]) / 4.0)) + _13948_v9117[_13948_v9117Offset + 13])) + (((- ((_13948_v9117[_13948_v9117Offset + 12] + _13948_v9117[_13948_v9117Offset + 13] + _13948_v9117[_13948_v9117Offset + 14] + _13948_v9117[_13948_v9117Offset + 15]) / 4.0)) + _13948_v9117[_13948_v9117Offset + 14]) * ((- ((_13948_v9117[_13948_v9117Offset + 12] + _13948_v9117[_13948_v9117Offset + 13] + _13948_v9117[_13948_v9117Offset + 14] + _13948_v9117[_13948_v9117Offset + 15]) / 4.0)) + _13948_v9117[_13948_v9117Offset + 14])) + (((- ((_13948_v9117[_13948_v9117Offset + 12] + _13948_v9117[_13948_v9117Offset + 13] + _13948_v9117[_13948_v9117Offset + 14] + _13948_v9117[_13948_v9117Offset + 15]) / 4.0)) + _13948_v9117[_13948_v9117Offset + 15]) * ((- ((_13948_v9117[_13948_v9117Offset + 12] + _13948_v9117[_13948_v9117Offset + 13] + _13948_v9117[_13948_v9117Offset + 14] + _13948_v9117[_13948_v9117Offset + 15]) / 4.0)) + _13948_v9117[_13948_v9117Offset + 15]))) / 4.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
