#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation148_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1998_v1958Offset = (int) offsetArr[0];
jint _2010_v1970Offset = (int) offsetArr[1];
jint _2002_v1962Offset = (int) offsetArr[2];
jint _2014_v1974Offset = (int) offsetArr[3];
jint _1998_v1958Size = (int) sizeArr[0];
jint _2010_v1970Size = (int) sizeArr[1];
jint _2002_v1962Size = (int) sizeArr[2];
jint _2014_v1974Size = (int) sizeArr[3];
jint _1998_v1958Dim0 = (int) dim0Arr[0];
jint _2010_v1970Dim0 = (int) dim0Arr[1];
jint _2002_v1962Dim0 = (int) dim0Arr[2];
jint _2014_v1974Dim0 = (int) dim0Arr[3];
double *_1998_v1958 = ((double *) argArr[0]);
double *_2010_v1970 = ((double *) argArr[1]);
double *_2002_v1962 = ((double *) argArr[2]);
double *_2014_v1974 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1998_v1958[_1998_v1958Offset] = 0.4;
_1998_v1958[_1998_v1958Offset + 1] = 1.0;
if (_1998_v1958[_1998_v1958Offset] <= 0.3333333333333333) {
_2002_v1962[_2002_v1962Offset] = 2.0;
}
else if (_1998_v1958[_1998_v1958Offset] <= 0.6666666666666666) {
_2002_v1962[_2002_v1962Offset] = 4.0;
}
else if (_1998_v1958[_1998_v1958Offset] <= 1.0) {
_2002_v1962[_2002_v1962Offset] = 8.0;
}
_2010_v1970[_2010_v1970Offset] = 0.8;
_2010_v1970[_2010_v1970Offset + 1] = 1.0;
if (_2010_v1970[_2010_v1970Offset] <= 0.3333333333333333) {
_2014_v1974[_2014_v1974Offset] = 2.0;
}
else if (_2010_v1970[_2010_v1970Offset] <= 0.6666666666666666) {
_2014_v1974[_2014_v1974Offset] = 4.0;
}
else if (_2010_v1970[_2010_v1970Offset] <= 1.0) {
_2014_v1974[_2014_v1974Offset] = 8.0;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
