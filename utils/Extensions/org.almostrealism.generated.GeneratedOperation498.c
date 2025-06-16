#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation498_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6608_v4936Offset = (int) offsetArr[0];
jint _6589_v4921Offset = (int) offsetArr[1];
jint _6608_v4936Size = (int) sizeArr[0];
jint _6589_v4921Size = (int) sizeArr[1];
jint _6608_v4936Dim0 = (int) dim0Arr[0];
jint _6589_v4921Dim0 = (int) dim0Arr[1];
double *_6608_v4936 = ((double *) argArr[0]);
double *_6589_v4921 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
double f_defaultTraversableExpressionComputation_6608_0 = (_6589_v4921[_6589_v4921Offset + 69] + _6589_v4921[_6589_v4921Offset + 70] + _6589_v4921[_6589_v4921Offset + 74] + _6589_v4921[_6589_v4921Offset + 71] + _6589_v4921[_6589_v4921Offset + 77] + _6589_v4921[_6589_v4921Offset + 73] + _6589_v4921[_6589_v4921Offset + 72] + _6589_v4921[_6589_v4921Offset + 79] + _6589_v4921[_6589_v4921Offset + 64] + _6589_v4921[_6589_v4921Offset + 65] + _6589_v4921[_6589_v4921Offset + 76] + _6589_v4921[_6589_v4921Offset + 66] + _6589_v4921[_6589_v4921Offset + 67] + _6589_v4921[_6589_v4921Offset + 68] + _6589_v4921[_6589_v4921Offset + 78] + _6589_v4921[_6589_v4921Offset + 75]) / 16.0;
_6608_v4936[_6608_v4936Offset] = ((((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 64]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 64])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 65]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 65])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 76]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 76])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 66]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 66])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 67]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 67])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 68]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 68])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 78]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 78])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 75]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 75])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 69]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 69])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 70]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 70])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 74]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 74])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 71]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 71])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 77]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 77])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 73]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 73])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 72]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 72])) + (((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 79]) * ((- f_defaultTraversableExpressionComputation_6608_0) + _6589_v4921[_6589_v4921Offset + 79]))) / 16.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
