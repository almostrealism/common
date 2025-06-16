#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation310_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3888_v3139Offset = (int) offsetArr[0];
jint _3886_v3134Offset = (int) offsetArr[1];
jint _3886_v3135Offset = (int) offsetArr[2];
jint _3887_v3137Offset = (int) offsetArr[3];
jint _3888_v3139Size = (int) sizeArr[0];
jint _3886_v3134Size = (int) sizeArr[1];
jint _3886_v3135Size = (int) sizeArr[2];
jint _3887_v3137Size = (int) sizeArr[3];
jint _3888_v3139Dim0 = (int) dim0Arr[0];
jint _3886_v3134Dim0 = (int) dim0Arr[1];
jint _3886_v3135Dim0 = (int) dim0Arr[2];
jint _3887_v3137Dim0 = (int) dim0Arr[3];
double *_3888_v3139 = ((double *) argArr[0]);
double *_3886_v3134 = ((double *) argArr[1]);
double *_3886_v3135 = ((double *) argArr[2]);
double *_3887_v3137 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3888_v3139[global_id + _3888_v3139Offset] = (_3886_v3134[global_id + _3886_v3134Offset] + _3886_v3135[global_id + _3886_v3135Offset]) * _3887_v3137[(global_id / 2) + _3887_v3137Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
