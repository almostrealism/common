#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation868_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13224_v8653Offset = (int) offsetArr[0];
jint _13224_v8654Offset = (int) offsetArr[1];
jint _13224_v8653Size = (int) sizeArr[0];
jint _13224_v8654Size = (int) sizeArr[1];
jint _13224_v8653Dim0 = (int) dim0Arr[0];
jint _13224_v8654Dim0 = (int) dim0Arr[1];
double *_13224_v8653 = ((double *) argArr[0]);
double *_13224_v8654 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13224_v8653[global_id + _13224_v8653Offset] = _13224_v8654[((((global_id % 16) / 2) * 8) + ((global_id / 16) * 2) + (global_id % 2)) + _13224_v8654Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
