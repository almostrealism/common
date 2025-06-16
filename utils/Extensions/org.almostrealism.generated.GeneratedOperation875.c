#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation875_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13238_v8465Offset = (int) offsetArr[0];
jint _13243_v8468Offset = (int) offsetArr[1];
jint _13246_v8473Offset = (int) offsetArr[2];
jint _13238_v8465Size = (int) sizeArr[0];
jint _13243_v8468Size = (int) sizeArr[1];
jint _13246_v8473Size = (int) sizeArr[2];
jint _13238_v8465Dim0 = (int) dim0Arr[0];
jint _13243_v8468Dim0 = (int) dim0Arr[1];
jint _13246_v8473Dim0 = (int) dim0Arr[2];
double *_13238_v8465 = ((double *) argArr[0]);
double *_13243_v8468 = ((double *) argArr[1]);
double *_13246_v8473 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13246_v8473[global_id + _13246_v8473Offset] = (- ((_13243_v8468[(global_id * 8) + _13243_v8468Offset + 1] + _13243_v8468[(global_id * 8) + _13243_v8468Offset + 2] + _13243_v8468[(global_id * 8) + _13243_v8468Offset + 3] + _13243_v8468[(global_id * 8) + _13243_v8468Offset + 4] + _13243_v8468[(global_id * 8) + _13243_v8468Offset + 5] + _13243_v8468[(global_id * 8) + _13243_v8468Offset + 6] + _13243_v8468[(global_id * 8) + _13243_v8468Offset + 7] + _13243_v8468[(global_id * 8) + _13243_v8468Offset]) * _13238_v8465[_13238_v8465Offset])) + _13246_v8473[global_id + _13246_v8473Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
