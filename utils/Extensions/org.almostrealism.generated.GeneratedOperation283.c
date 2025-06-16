#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation283_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3484_v2948Offset = (int) offsetArr[0];
jint _3434_v2918Offset = (int) offsetArr[1];
jint _3479_v2938Offset = (int) offsetArr[2];
jint _3484_v2948Size = (int) sizeArr[0];
jint _3434_v2918Size = (int) sizeArr[1];
jint _3479_v2938Size = (int) sizeArr[2];
jint _3484_v2948Dim0 = (int) dim0Arr[0];
jint _3434_v2918Dim0 = (int) dim0Arr[1];
jint _3479_v2938Dim0 = (int) dim0Arr[2];
double *_3484_v2948 = ((double *) argArr[0]);
double *_3434_v2918 = ((double *) argArr[1]);
double *_3479_v2938 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3484_v2948[global_id + _3484_v2948Offset] = (((_3479_v2938[((global_id % 2) * 2) + _3479_v2938Offset + 1] + _3479_v2938[((global_id % 2) * 2) + _3479_v2938Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * pow(pow(((_3434_v2918[_3434_v2918Offset] + _3434_v2918[_3434_v2918Offset + 1]) / 2.0) + 1.0E-5, 0.5), -1.0);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
