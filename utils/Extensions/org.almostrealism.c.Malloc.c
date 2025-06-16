#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT jlong JNICALL Java_org_almostrealism_c_Malloc_apply (JNIEnv* env, jobject thisObject, jint len) {
	return (jlong) malloc((size_t) len);
}
