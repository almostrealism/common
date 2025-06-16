#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation53_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _821_v578Offset = (int) offsetArr[0];
jint _851_v587Offset = (int) offsetArr[1];
jint _864_v597Offset = (int) offsetArr[2];
jint _821_v578Size = (int) sizeArr[0];
jint _851_v587Size = (int) sizeArr[1];
jint _864_v597Size = (int) sizeArr[2];
jint _821_v578Dim0 = (int) dim0Arr[0];
jint _851_v587Dim0 = (int) dim0Arr[1];
jint _864_v597Dim0 = (int) dim0Arr[2];
double *_821_v578 = ((double *) argArr[0]);
double *_851_v587 = ((double *) argArr[1]);
double *_864_v597 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_864_v597[global_id + _864_v597Offset] = ((sin(_821_v578[global_id + _821_v578Offset] / 0.0022727272727272726)) * 0.9) + ((sin(_851_v587[global_id + _851_v587Offset] / 0.0017026203326920128)) * 0.6);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
