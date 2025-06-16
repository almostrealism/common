#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation523_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7177_v5111Offset = (int) offsetArr[0];
jint _7127_v5081Offset = (int) offsetArr[1];
jint _7172_v5101Offset = (int) offsetArr[2];
jint _7177_v5111Size = (int) sizeArr[0];
jint _7127_v5081Size = (int) sizeArr[1];
jint _7172_v5101Size = (int) sizeArr[2];
jint _7177_v5111Dim0 = (int) dim0Arr[0];
jint _7127_v5081Dim0 = (int) dim0Arr[1];
jint _7172_v5101Dim0 = (int) dim0Arr[2];
double *_7177_v5111 = ((double *) argArr[0]);
double *_7127_v5081 = ((double *) argArr[1]);
double *_7172_v5101 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7177_v5111[global_id + _7177_v5111Offset] = (((_7172_v5101[((global_id % 2) * 2) + _7172_v5101Offset + 1] + _7172_v5101[((global_id % 2) * 2) + _7172_v5101Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * pow(pow(((_7127_v5081[_7127_v5081Offset] + _7127_v5081[_7127_v5081Offset + 1]) / 2.0) + 1.0E-5, 0.5), -1.0);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
