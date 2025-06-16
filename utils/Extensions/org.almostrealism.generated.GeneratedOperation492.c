#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation492_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6528_v4881Offset = (int) offsetArr[0];
jint _6509_v4866Offset = (int) offsetArr[1];
jint _6528_v4881Size = (int) sizeArr[0];
jint _6509_v4866Size = (int) sizeArr[1];
jint _6528_v4881Dim0 = (int) dim0Arr[0];
jint _6509_v4866Dim0 = (int) dim0Arr[1];
double *_6528_v4881 = ((double *) argArr[0]);
double *_6509_v4866 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
double f_defaultTraversableExpressionComputation_6528_0 = (_6509_v4866[_6509_v4866Offset + 53] + _6509_v4866[_6509_v4866Offset + 54] + _6509_v4866[_6509_v4866Offset + 58] + _6509_v4866[_6509_v4866Offset + 55] + _6509_v4866[_6509_v4866Offset + 61] + _6509_v4866[_6509_v4866Offset + 57] + _6509_v4866[_6509_v4866Offset + 56] + _6509_v4866[_6509_v4866Offset + 63] + _6509_v4866[_6509_v4866Offset + 48] + _6509_v4866[_6509_v4866Offset + 49] + _6509_v4866[_6509_v4866Offset + 60] + _6509_v4866[_6509_v4866Offset + 50] + _6509_v4866[_6509_v4866Offset + 51] + _6509_v4866[_6509_v4866Offset + 52] + _6509_v4866[_6509_v4866Offset + 62] + _6509_v4866[_6509_v4866Offset + 59]) / 16.0;
_6528_v4881[_6528_v4881Offset] = ((((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 48]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 48])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 49]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 49])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 60]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 60])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 50]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 50])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 51]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 51])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 52]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 52])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 62]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 62])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 59]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 59])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 53]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 53])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 54]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 54])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 58]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 58])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 55]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 55])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 61]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 61])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 57]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 57])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 56]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 56])) + (((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 63]) * ((- f_defaultTraversableExpressionComputation_6528_0) + _6509_v4866[_6509_v4866Offset + 63]))) / 16.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
