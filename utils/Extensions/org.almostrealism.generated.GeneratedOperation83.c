#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation83_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1072_v744Offset = (int) offsetArr[0];
jint _1093_v753Offset = (int) offsetArr[1];
jint _1106_v763Offset = (int) offsetArr[2];
jint _1072_v744Size = (int) sizeArr[0];
jint _1093_v753Size = (int) sizeArr[1];
jint _1106_v763Size = (int) sizeArr[2];
jint _1072_v744Dim0 = (int) dim0Arr[0];
jint _1093_v753Dim0 = (int) dim0Arr[1];
jint _1106_v763Dim0 = (int) dim0Arr[2];
double *_1072_v744 = ((double *) argArr[0]);
double *_1093_v753 = ((double *) argArr[1]);
double *_1106_v763 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1106_v763[global_id + _1106_v763Offset] = ((sin(_1072_v744[global_id + _1072_v744Offset] / 0.0022727272727272726)) * 0.9) + ((sin(_1093_v753[global_id + _1093_v753Offset] / 0.0017026203326920128)) * 0.6);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
