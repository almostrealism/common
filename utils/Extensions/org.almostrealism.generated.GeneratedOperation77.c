#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation77_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1016_v708Offset = (int) offsetArr[0];
jint _1046_v717Offset = (int) offsetArr[1];
jint _1059_v727Offset = (int) offsetArr[2];
jint _1016_v708Size = (int) sizeArr[0];
jint _1046_v717Size = (int) sizeArr[1];
jint _1059_v727Size = (int) sizeArr[2];
jint _1016_v708Dim0 = (int) dim0Arr[0];
jint _1046_v717Dim0 = (int) dim0Arr[1];
jint _1059_v727Dim0 = (int) dim0Arr[2];
double *_1016_v708 = ((double *) argArr[0]);
double *_1046_v717 = ((double *) argArr[1]);
double *_1059_v727 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1059_v727[global_id + _1059_v727Offset] = ((sin(_1016_v708[global_id + _1016_v708Offset] / 0.0022727272727272726)) * 0.9) + ((sin(_1046_v717[global_id + _1046_v717Offset] / 0.0017026203326920128)) * 0.6);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
