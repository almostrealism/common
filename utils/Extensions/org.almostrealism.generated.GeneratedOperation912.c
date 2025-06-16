#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation912_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13846_v9034Offset = (int) offsetArr[0];
jint _13841_v9031Offset = (int) offsetArr[1];
jint _13846_v9034Size = (int) sizeArr[0];
jint _13841_v9031Size = (int) sizeArr[1];
jint _13846_v9034Dim0 = (int) dim0Arr[0];
jint _13841_v9031Dim0 = (int) dim0Arr[1];
double *_13846_v9034 = ((double *) argArr[0]);
double *_13841_v9031 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13846_v9034[global_id + _13846_v9034Offset] = (_13841_v9031[global_id + _13841_v9031Offset] + -0.07366138504172272) / 0.008341635880629928;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
