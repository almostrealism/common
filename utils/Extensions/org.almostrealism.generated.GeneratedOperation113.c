#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation113_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1308_v1038Offset = (int) offsetArr[0];
jint _1243_v943Offset = (int) offsetArr[1];
jint _1308_v1038Size = (int) sizeArr[0];
jint _1243_v943Size = (int) sizeArr[1];
jint _1308_v1038Dim0 = (int) dim0Arr[0];
jint _1243_v943Dim0 = (int) dim0Arr[1];
double *_1308_v1038 = ((double *) argArr[0]);
double *_1243_v943 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1308_v1038[_1308_v1038Offset] = (- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 3];
_1308_v1038[_1308_v1038Offset + 1] = (- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 4];
_1308_v1038[_1308_v1038Offset + 2] = (- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 5];
_1308_v1038[_1308_v1038Offset + 3] = (- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 6];
_1308_v1038[_1308_v1038Offset + 4] = (- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 7];
_1308_v1038[_1308_v1038Offset + 5] = (- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 8];
_1308_v1038[_1308_v1038Offset + 6] = _1243_v943[_1243_v943Offset];
_1308_v1038[_1308_v1038Offset + 7] = _1243_v943[_1243_v943Offset + 1];
_1308_v1038[_1308_v1038Offset + 8] = _1243_v943[_1243_v943Offset + 2];
_1308_v1038[_1308_v1038Offset + 9] = pow(pow(pow((- (((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 5]) * ((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 7]))) + (((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 4]) * ((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 8])), 2.0) + pow((- (((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 3]) * ((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 8]))) + (((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 5]) * ((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 6])), 2.0) + pow((- (((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 4]) * ((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 6]))) + (((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 3]) * ((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 7])), 2.0), 0.5), -1.0) * ((- (((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 5]) * ((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 7]))) + (((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 4]) * ((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 8])));
_1308_v1038[_1308_v1038Offset + 10] = pow(pow(pow((- (((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 5]) * ((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 7]))) + (((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 4]) * ((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 8])), 2.0) + pow((- (((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 3]) * ((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 8]))) + (((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 5]) * ((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 6])), 2.0) + pow((- (((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 4]) * ((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 6]))) + (((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 3]) * ((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 7])), 2.0), 0.5), -1.0) * ((- (((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 3]) * ((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 8]))) + (((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 5]) * ((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 6])));
_1308_v1038[_1308_v1038Offset + 11] = pow(pow(pow((- (((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 5]) * ((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 7]))) + (((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 4]) * ((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 8])), 2.0) + pow((- (((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 3]) * ((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 8]))) + (((- _1243_v943[_1243_v943Offset + 2]) + _1243_v943[_1243_v943Offset + 5]) * ((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 6])), 2.0) + pow((- (((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 4]) * ((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 6]))) + (((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 3]) * ((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 7])), 2.0), 0.5), -1.0) * ((- (((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 4]) * ((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 6]))) + (((- _1243_v943[_1243_v943Offset]) + _1243_v943[_1243_v943Offset + 3]) * ((- _1243_v943[_1243_v943Offset + 1]) + _1243_v943[_1243_v943Offset + 7])));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
