#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation421_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5470_v4234Offset = (int) offsetArr[0];
jint _5466_v4226Offset = (int) offsetArr[1];
jint _5469_v4231Offset = (int) offsetArr[2];
jint _5470_v4234Size = (int) sizeArr[0];
jint _5466_v4226Size = (int) sizeArr[1];
jint _5469_v4231Size = (int) sizeArr[2];
jint _5470_v4234Dim0 = (int) dim0Arr[0];
jint _5466_v4226Dim0 = (int) dim0Arr[1];
jint _5469_v4231Dim0 = (int) dim0Arr[2];
double *_5470_v4234 = ((double *) argArr[0]);
double *_5466_v4226 = ((double *) argArr[1]);
double *_5469_v4231 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5470_v4234[global_id + _5470_v4234Offset] = ((- (_5466_v4226[_5466_v4226Offset] / 30.0)) + _5469_v4231[global_id + _5469_v4231Offset + 30]) * ((- (_5466_v4226[_5466_v4226Offset] / 30.0)) + _5469_v4231[global_id + _5469_v4231Offset + 30]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
