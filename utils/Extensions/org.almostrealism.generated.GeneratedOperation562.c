#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation562_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7914_v5691Offset = (int) offsetArr[0];
jint _7914_v5692Offset = (int) offsetArr[1];
jint _7914_v5694Offset = (int) offsetArr[2];
jint _7914_v5691Size = (int) sizeArr[0];
jint _7914_v5692Size = (int) sizeArr[1];
jint _7914_v5694Size = (int) sizeArr[2];
jint _7914_v5691Dim0 = (int) dim0Arr[0];
jint _7914_v5692Dim0 = (int) dim0Arr[1];
jint _7914_v5694Dim0 = (int) dim0Arr[2];
double *_7914_v5691 = ((double *) argArr[0]);
double *_7914_v5692 = ((double *) argArr[1]);
double *_7914_v5694 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7914_v5691[global_id + _7914_v5691Offset] = _7914_v5692[global_id + _7914_v5692Offset] * _7914_v5694[global_id + _7914_v5694Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
