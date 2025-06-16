#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation551_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7782_v5365Offset = (int) offsetArr[0];
jint _7783_v5366Offset = (int) offsetArr[1];
jint _7782_v5365Size = (int) sizeArr[0];
jint _7783_v5366Size = (int) sizeArr[1];
jint _7782_v5365Dim0 = (int) dim0Arr[0];
jint _7783_v5366Dim0 = (int) dim0Arr[1];
double *_7782_v5365 = ((double *) argArr[0]);
double *_7783_v5366 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7783_v5366[global_id + _7783_v5366Offset] = _7782_v5365[(global_id * 3) + _7782_v5365Offset + 1] + _7782_v5365[(global_id * 3) + _7782_v5365Offset + 2] + _7782_v5365[(global_id * 3) + _7782_v5365Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
