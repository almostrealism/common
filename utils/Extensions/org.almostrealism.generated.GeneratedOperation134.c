#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation134_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1893_v1849Offset = (int) offsetArr[0];
jint _1883_v1838Offset = (int) offsetArr[1];
jint _1883_v1839Offset = (int) offsetArr[2];
jint _1893_v1850Offset = (int) offsetArr[3];
jint _1893_v1849Size = (int) sizeArr[0];
jint _1883_v1838Size = (int) sizeArr[1];
jint _1883_v1839Size = (int) sizeArr[2];
jint _1893_v1850Size = (int) sizeArr[3];
jint _1893_v1849Dim0 = (int) dim0Arr[0];
jint _1883_v1838Dim0 = (int) dim0Arr[1];
jint _1883_v1839Dim0 = (int) dim0Arr[2];
jint _1893_v1850Dim0 = (int) dim0Arr[3];
double *_1893_v1849 = ((double *) argArr[0]);
double *_1883_v1838 = ((double *) argArr[1]);
double *_1883_v1839 = ((double *) argArr[2]);
double *_1893_v1850 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1893_v1849[global_id + _1893_v1849Offset] = pow(pow((_1883_v1838[_1883_v1838Offset] * _1883_v1839[_1883_v1839Offset]) + (_1883_v1838[_1883_v1838Offset + 1] * _1883_v1839[_1883_v1839Offset + 1]), 0.5), -1.0) * _1893_v1850[global_id + _1893_v1850Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
