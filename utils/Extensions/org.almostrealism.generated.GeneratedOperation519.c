#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation519_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7164_v5147Offset = (int) offsetArr[0];
jint _7127_v5115Offset = (int) offsetArr[1];
jint _7161_v5141Offset = (int) offsetArr[2];
jint _7164_v5147Size = (int) sizeArr[0];
jint _7127_v5115Size = (int) sizeArr[1];
jint _7161_v5141Size = (int) sizeArr[2];
jint _7164_v5147Dim0 = (int) dim0Arr[0];
jint _7127_v5115Dim0 = (int) dim0Arr[1];
jint _7161_v5141Dim0 = (int) dim0Arr[2];
double *_7164_v5147 = ((double *) argArr[0]);
double *_7127_v5115 = ((double *) argArr[1]);
double *_7161_v5141 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7164_v5147[global_id + _7164_v5147Offset] = (- pow(pow(((_7127_v5115[_7127_v5115Offset] + _7127_v5115[_7127_v5115Offset + 1]) / 2.0) + 1.0E-5, 0.5), -2.0)) * ((pow(((_7127_v5115[_7127_v5115Offset] + _7127_v5115[_7127_v5115Offset + 1]) / 2.0) + 1.0E-5, -0.5) * 0.5) * ((_7161_v5141[(global_id * 2) + _7161_v5141Offset + 1] + _7161_v5141[(global_id * 2) + _7161_v5141Offset]) * 0.5));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
