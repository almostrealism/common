#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation284_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3487_v2895Offset = (int) offsetArr[0];
jint _3485_v2890Offset = (int) offsetArr[1];
jint _3485_v2891Offset = (int) offsetArr[2];
jint _3486_v2893Offset = (int) offsetArr[3];
jint _3487_v2895Size = (int) sizeArr[0];
jint _3485_v2890Size = (int) sizeArr[1];
jint _3485_v2891Size = (int) sizeArr[2];
jint _3486_v2893Size = (int) sizeArr[3];
jint _3487_v2895Dim0 = (int) dim0Arr[0];
jint _3485_v2890Dim0 = (int) dim0Arr[1];
jint _3485_v2891Dim0 = (int) dim0Arr[2];
jint _3486_v2893Dim0 = (int) dim0Arr[3];
double *_3487_v2895 = ((double *) argArr[0]);
double *_3485_v2890 = ((double *) argArr[1]);
double *_3485_v2891 = ((double *) argArr[2]);
double *_3486_v2893 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3487_v2895[global_id + _3487_v2895Offset] = (_3485_v2890[global_id + _3485_v2890Offset] + _3485_v2891[global_id + _3485_v2891Offset]) * _3486_v2893[(global_id / 2) + _3486_v2893Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
