#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation306_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3874_v3158Offset = (int) offsetArr[0];
jint _3823_v3143Offset = (int) offsetArr[1];
jint _3873_v3157Offset = (int) offsetArr[2];
jint _3874_v3158Size = (int) sizeArr[0];
jint _3823_v3143Size = (int) sizeArr[1];
jint _3873_v3157Size = (int) sizeArr[2];
jint _3874_v3158Dim0 = (int) dim0Arr[0];
jint _3823_v3143Dim0 = (int) dim0Arr[1];
jint _3873_v3157Dim0 = (int) dim0Arr[2];
double *_3874_v3158 = ((double *) argArr[0]);
double *_3823_v3143 = ((double *) argArr[1]);
double *_3873_v3157 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3874_v3158[global_id + _3874_v3158Offset] = ((- ((_3823_v3143[_3823_v3143Offset] + _3823_v3143[_3823_v3143Offset + 1]) / 2.0)) + _3823_v3143[(global_id / 2) + _3823_v3143Offset]) * _3873_v3157[(global_id % 2) + _3873_v3157Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
