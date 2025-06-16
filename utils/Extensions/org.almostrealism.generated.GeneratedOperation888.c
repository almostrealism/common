#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation888_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13405_v8786Offset = (int) offsetArr[0];
jint _13386_v8771Offset = (int) offsetArr[1];
jint _13405_v8786Size = (int) sizeArr[0];
jint _13386_v8771Size = (int) sizeArr[1];
jint _13405_v8786Dim0 = (int) dim0Arr[0];
jint _13386_v8771Dim0 = (int) dim0Arr[1];
double *_13405_v8786 = ((double *) argArr[0]);
double *_13386_v8771 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13405_v8786[_13405_v8786Offset] = ((((- ((_13386_v8771[_13386_v8771Offset + 6] + _13386_v8771[_13386_v8771Offset + 7]) / 2.0)) + _13386_v8771[_13386_v8771Offset + 6]) * ((- ((_13386_v8771[_13386_v8771Offset + 6] + _13386_v8771[_13386_v8771Offset + 7]) / 2.0)) + _13386_v8771[_13386_v8771Offset + 6])) + (((- ((_13386_v8771[_13386_v8771Offset + 6] + _13386_v8771[_13386_v8771Offset + 7]) / 2.0)) + _13386_v8771[_13386_v8771Offset + 7]) * ((- ((_13386_v8771[_13386_v8771Offset + 6] + _13386_v8771[_13386_v8771Offset + 7]) / 2.0)) + _13386_v8771[_13386_v8771Offset + 7]))) / 2.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
