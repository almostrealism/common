#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation69_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _951_v664Offset = (int) offsetArr[0];
jint _981_v673Offset = (int) offsetArr[1];
jint _994_v683Offset = (int) offsetArr[2];
jint _951_v664Size = (int) sizeArr[0];
jint _981_v673Size = (int) sizeArr[1];
jint _994_v683Size = (int) sizeArr[2];
jint _951_v664Dim0 = (int) dim0Arr[0];
jint _981_v673Dim0 = (int) dim0Arr[1];
jint _994_v683Dim0 = (int) dim0Arr[2];
double *_951_v664 = ((double *) argArr[0]);
double *_981_v673 = ((double *) argArr[1]);
double *_994_v683 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_994_v683[global_id + _994_v683Offset] = ((sin(_951_v664[global_id + _951_v664Offset] / 0.0022727272727272726)) * 0.9) + ((sin(_981_v673[global_id + _981_v673Offset] / 0.0017026203326920128)) * 0.6);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
