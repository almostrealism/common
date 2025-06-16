#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation474_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6288_v4716Offset = (int) offsetArr[0];
jint _6269_v4701Offset = (int) offsetArr[1];
jint _6288_v4716Size = (int) sizeArr[0];
jint _6269_v4701Size = (int) sizeArr[1];
jint _6288_v4716Dim0 = (int) dim0Arr[0];
jint _6269_v4701Dim0 = (int) dim0Arr[1];
double *_6288_v4716 = ((double *) argArr[0]);
double *_6269_v4701 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
double f_defaultTraversableExpressionComputation_6288_0 = (_6269_v4701[_6269_v4701Offset + 5] + _6269_v4701[_6269_v4701Offset + 6] + _6269_v4701[_6269_v4701Offset + 10] + _6269_v4701[_6269_v4701Offset + 7] + _6269_v4701[_6269_v4701Offset + 13] + _6269_v4701[_6269_v4701Offset + 9] + _6269_v4701[_6269_v4701Offset + 8] + _6269_v4701[_6269_v4701Offset + 15] + _6269_v4701[_6269_v4701Offset] + _6269_v4701[_6269_v4701Offset + 1] + _6269_v4701[_6269_v4701Offset + 12] + _6269_v4701[_6269_v4701Offset + 2] + _6269_v4701[_6269_v4701Offset + 3] + _6269_v4701[_6269_v4701Offset + 4] + _6269_v4701[_6269_v4701Offset + 14] + _6269_v4701[_6269_v4701Offset + 11]) / 16.0;
_6288_v4716[_6288_v4716Offset] = ((((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 1]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 1])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 12]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 12])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 2]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 2])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 3]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 3])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 4]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 4])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 14]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 14])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 11]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 11])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 5]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 5])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 6]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 6])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 10]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 10])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 7]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 7])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 13]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 13])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 9]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 9])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 8]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 8])) + (((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 15]) * ((- f_defaultTraversableExpressionComputation_6288_0) + _6269_v4701[_6269_v4701Offset + 15]))) / 16.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
