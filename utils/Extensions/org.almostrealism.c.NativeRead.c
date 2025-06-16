#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT jdoubleArray JNICALL Java_org_almostrealism_c_NativeRead_apply (JNIEnv* env, jobject thisObject, jlong arg, jint offset, jint len) {
	double* input = (double *) arg;
	jdoubleArray output = (*env)->NewDoubleArray(env, (jsize) len);
	for (int i = 0; i < len; i++) {
		(*env)->SetDoubleArrayRegion(env, output, i, 1, (const jdouble*)&input[offset + i]);
	}
return output;
}
