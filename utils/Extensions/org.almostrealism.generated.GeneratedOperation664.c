#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation664_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10295_v6778Offset = (int) offsetArr[0];
jint _10290_v6775Offset = (int) offsetArr[1];
jint _10295_v6778Size = (int) sizeArr[0];
jint _10290_v6775Size = (int) sizeArr[1];
jint _10295_v6778Dim0 = (int) dim0Arr[0];
jint _10290_v6775Dim0 = (int) dim0Arr[1];
double *_10295_v6778 = ((double *) argArr[0]);
double *_10290_v6775 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10295_v6778[global_id + _10295_v6778Offset] = (_10290_v6775[global_id + _10290_v6775Offset + 8] + -0.06584226782390418) / 0.021412865926459853;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
