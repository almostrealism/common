#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation825_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12572_v8266Offset = (int) offsetArr[0];
jint _12538_v8226Offset = (int) offsetArr[1];
jint _12541_v8231Offset = (int) offsetArr[2];
jint _12558_v8239Offset = (int) offsetArr[3];
jint _12567_v8255Offset = (int) offsetArr[4];
jint _12572_v8266Size = (int) sizeArr[0];
jint _12538_v8226Size = (int) sizeArr[1];
jint _12541_v8231Size = (int) sizeArr[2];
jint _12558_v8239Size = (int) sizeArr[3];
jint _12567_v8255Size = (int) sizeArr[4];
jint _12572_v8266Dim0 = (int) dim0Arr[0];
jint _12538_v8226Dim0 = (int) dim0Arr[1];
jint _12541_v8231Dim0 = (int) dim0Arr[2];
jint _12558_v8239Dim0 = (int) dim0Arr[3];
jint _12567_v8255Dim0 = (int) dim0Arr[4];
double *_12572_v8266 = ((double *) argArr[0]);
double *_12538_v8226 = ((double *) argArr[1]);
double *_12541_v8231 = ((double *) argArr[2]);
double *_12558_v8239 = ((double *) argArr[3]);
double *_12567_v8255 = ((double *) argArr[4]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12572_v8266[global_id + _12572_v8266Offset] = ((((((- (global_id % 120)) + (global_id / 120)) == 0) ? 1 : 0) + (_12558_v8239[(((global_id / 3600) * 120) + (global_id % 120)) + _12558_v8239Offset] * -0.03333333333333333)) * ((- (_12538_v8226[(global_id / 3600) + _12538_v8226Offset] / 30.0)) + _12541_v8231[(global_id / 120) + _12541_v8231Offset])) + ((((((- (global_id % 120)) + (global_id / 120)) == 0) ? 1 : 0) + (_12567_v8255[(((global_id / 3600) * 120) + (global_id % 120)) + _12567_v8255Offset] * -0.03333333333333333)) * ((- (_12538_v8226[(global_id / 3600) + _12538_v8226Offset] / 30.0)) + _12541_v8231[(global_id / 120) + _12541_v8231Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
