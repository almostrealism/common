#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation146_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1940_v1900Offset = (int) offsetArr[0];
jint _1942_v1914Offset = (int) offsetArr[1];
jint _1945_v1927Offset = (int) offsetArr[2];
jint _1956_v1938Offset = (int) offsetArr[3];
jint _1967_v1904Offset = (int) offsetArr[4];
jint _1977_v1917Offset = (int) offsetArr[5];
jint _1940_v1900Size = (int) sizeArr[0];
jint _1942_v1914Size = (int) sizeArr[1];
jint _1945_v1927Size = (int) sizeArr[2];
jint _1956_v1938Size = (int) sizeArr[3];
jint _1967_v1904Size = (int) sizeArr[4];
jint _1977_v1917Size = (int) sizeArr[5];
jint _1940_v1900Dim0 = (int) dim0Arr[0];
jint _1942_v1914Dim0 = (int) dim0Arr[1];
jint _1945_v1927Dim0 = (int) dim0Arr[2];
jint _1956_v1938Dim0 = (int) dim0Arr[3];
jint _1967_v1904Dim0 = (int) dim0Arr[4];
jint _1977_v1917Dim0 = (int) dim0Arr[5];
double *_1940_v1900 = ((double *) argArr[0]);
double *_1942_v1914 = ((double *) argArr[1]);
double *_1945_v1927 = ((double *) argArr[2]);
double *_1956_v1938 = ((double *) argArr[3]);
double *_1967_v1904 = ((double *) argArr[4]);
double *_1977_v1917 = ((double *) argArr[5]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1940_v1900[_1940_v1900Offset] = 0.4;
_1940_v1900[_1940_v1900Offset + 1] = 1.0;
if (_1940_v1900[_1940_v1900Offset] <= 0.3333333333333333) {
_1967_v1904[_1967_v1904Offset] = 2.0;
}
else if (_1940_v1900[_1940_v1900Offset] <= 0.6666666666666666) {
_1967_v1904[_1967_v1904Offset] = 4.0;
}
else if (_1940_v1900[_1940_v1900Offset] <= 1.0) {
_1967_v1904[_1967_v1904Offset] = 8.0;
}
_1942_v1914[_1942_v1914Offset] = 0.8;
_1942_v1914[_1942_v1914Offset + 1] = 1;
if (_1942_v1914[_1942_v1914Offset] <= 0.3333333333333333) {
_1977_v1917[_1977_v1917Offset] = 4.0;
}
else if (_1942_v1914[_1942_v1914Offset] <= 0.6666666666666666) {
_1977_v1917[_1977_v1917Offset] = 8.0;
}
else if (_1942_v1914[_1942_v1914Offset] <= 1.0) {
_1977_v1917[_1977_v1917Offset] = 16.0;
}
_1940_v1900[_1940_v1900Offset] = 0.4;
_1940_v1900[_1940_v1900Offset + 1] = 1.0;
if (_1940_v1900[_1940_v1900Offset] <= 0.3333333333333333) {
_1945_v1927[_1945_v1927Offset] = 4.0;
}
else if (_1940_v1900[_1940_v1900Offset] <= 0.6666666666666666) {
_1945_v1927[_1945_v1927Offset] = 8.0;
}
else if (_1940_v1900[_1940_v1900Offset] <= 1.0) {
_1945_v1927[_1945_v1927Offset] = 16.0;
}
_1942_v1914[_1942_v1914Offset] = 0.8;
_1942_v1914[_1942_v1914Offset + 1] = 1;
if (_1942_v1914[_1942_v1914Offset] <= 0.3333333333333333) {
_1956_v1938[_1956_v1938Offset] = 2.0;
}
else if (_1942_v1914[_1942_v1914Offset] <= 0.6666666666666666) {
_1956_v1938[_1956_v1938Offset] = 4.0;
}
else if (_1942_v1914[_1942_v1914Offset] <= 1.0) {
_1956_v1938[_1956_v1938Offset] = 8.0;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
