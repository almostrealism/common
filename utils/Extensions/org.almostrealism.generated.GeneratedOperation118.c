#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation118_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1592_v1426Offset = (int) offsetArr[0];
jint _1527_v1331Offset = (int) offsetArr[1];
jint _1592_v1426Size = (int) sizeArr[0];
jint _1527_v1331Size = (int) sizeArr[1];
jint _1592_v1426Dim0 = (int) dim0Arr[0];
jint _1527_v1331Dim0 = (int) dim0Arr[1];
double *_1592_v1426 = ((double *) argArr[0]);
double *_1527_v1331 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1592_v1426[_1592_v1426Offset] = (- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 3];
_1592_v1426[_1592_v1426Offset + 1] = (- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 4];
_1592_v1426[_1592_v1426Offset + 2] = (- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 5];
_1592_v1426[_1592_v1426Offset + 3] = (- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 6];
_1592_v1426[_1592_v1426Offset + 4] = (- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 7];
_1592_v1426[_1592_v1426Offset + 5] = (- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 8];
_1592_v1426[_1592_v1426Offset + 6] = _1527_v1331[_1527_v1331Offset];
_1592_v1426[_1592_v1426Offset + 7] = _1527_v1331[_1527_v1331Offset + 1];
_1592_v1426[_1592_v1426Offset + 8] = _1527_v1331[_1527_v1331Offset + 2];
_1592_v1426[_1592_v1426Offset + 9] = pow(pow(pow((- (((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 5]) * ((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 7]))) + (((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 4]) * ((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 8])), 2.0) + pow((- (((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 3]) * ((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 8]))) + (((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 5]) * ((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 6])), 2.0) + pow((- (((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 4]) * ((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 6]))) + (((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 3]) * ((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 7])), 2.0), 0.5), -1.0) * ((- (((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 5]) * ((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 7]))) + (((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 4]) * ((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 8])));
_1592_v1426[_1592_v1426Offset + 10] = pow(pow(pow((- (((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 5]) * ((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 7]))) + (((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 4]) * ((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 8])), 2.0) + pow((- (((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 3]) * ((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 8]))) + (((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 5]) * ((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 6])), 2.0) + pow((- (((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 4]) * ((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 6]))) + (((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 3]) * ((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 7])), 2.0), 0.5), -1.0) * ((- (((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 3]) * ((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 8]))) + (((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 5]) * ((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 6])));
_1592_v1426[_1592_v1426Offset + 11] = pow(pow(pow((- (((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 5]) * ((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 7]))) + (((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 4]) * ((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 8])), 2.0) + pow((- (((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 3]) * ((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 8]))) + (((- _1527_v1331[_1527_v1331Offset + 2]) + _1527_v1331[_1527_v1331Offset + 5]) * ((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 6])), 2.0) + pow((- (((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 4]) * ((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 6]))) + (((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 3]) * ((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 7])), 2.0), 0.5), -1.0) * ((- (((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 4]) * ((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 6]))) + (((- _1527_v1331[_1527_v1331Offset]) + _1527_v1331[_1527_v1331Offset + 3]) * ((- _1527_v1331[_1527_v1331Offset + 1]) + _1527_v1331[_1527_v1331Offset + 7])));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
