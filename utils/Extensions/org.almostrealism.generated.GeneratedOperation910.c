#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation910_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13800_v8811Offset = (int) offsetArr[0];
jint _13805_v8814Offset = (int) offsetArr[1];
jint _13808_v8819Offset = (int) offsetArr[2];
jint _13800_v8811Size = (int) sizeArr[0];
jint _13805_v8814Size = (int) sizeArr[1];
jint _13808_v8819Size = (int) sizeArr[2];
jint _13800_v8811Dim0 = (int) dim0Arr[0];
jint _13805_v8814Dim0 = (int) dim0Arr[1];
jint _13808_v8819Dim0 = (int) dim0Arr[2];
double *_13800_v8811 = ((double *) argArr[0]);
double *_13805_v8814 = ((double *) argArr[1]);
double *_13808_v8819 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13808_v8819[global_id + _13808_v8819Offset] = (- ((_13805_v8814[(global_id * 16) + _13805_v8814Offset + 8] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 1] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 13] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 2] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 3] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 4] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 15] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 12] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 5] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 6] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 11] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 7] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 14] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 10] + _13805_v8814[(global_id * 16) + _13805_v8814Offset + 9] + _13805_v8814[(global_id * 16) + _13805_v8814Offset]) * _13800_v8811[_13800_v8811Offset])) + _13808_v8819[global_id + _13808_v8819Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
