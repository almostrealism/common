#include <stdio.h>
#include <graalvm/llvm/polyglot.h>

int main() {
    void *arrayType = polyglot_java_type("int[]");
    void *array = polyglot_new_instance(arrayType, 4);
    polyglot_set_array_element(array, 2, 42);
    int element = polyglot_as_i32(polyglot_get_array_element(array, 2));
    printf("%d\n", element);
    return element;
}
