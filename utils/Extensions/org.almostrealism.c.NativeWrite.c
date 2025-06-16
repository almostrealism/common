#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_c_NativeWrite_apply (JNIEnv* env, jobject thisObject, jlong arg, jint offset, jdoubleArray target, jint toffset, jint len) {
	double* input = (*env)->GetDoubleArrayElements(env, target, 0);
	double* output = (double *) arg;
	for (int i = 0; i < len; i++) {
		output[offset + i] = input[toffset + i];
	}	free(input);
}
