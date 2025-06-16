#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation287_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3494_v3072Offset = (int) offsetArr[0];
jint _3493_v3070Offset = (int) offsetArr[1];
jint _3494_v3072Size = (int) sizeArr[0];
jint _3493_v3070Size = (int) sizeArr[1];
jint _3494_v3072Dim0 = (int) dim0Arr[0];
jint _3493_v3070Dim0 = (int) dim0Arr[1];
double *_3494_v3072 = ((double *) argArr[0]);
double *_3493_v3070 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3494_v3072[global_id + _3494_v3072Offset] = (((- (global_id % 2)) + (global_id / 2)) == 0) ? _3493_v3070[(global_id / 2) + _3493_v3070Offset] : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
