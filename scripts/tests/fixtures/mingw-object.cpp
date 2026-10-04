// Source of mingw-object.a, the fixture windows-mingw-objects.test.sh rewrites. Compiled for
// MinGW the way Kotlin/Native compiles its runtime: one section per function, so every
// function's unwind data is a COMDAT of its own, and a static constructor.
//
//   clang++ --target=x86_64-w64-mingw32 -ffunction-sections -O1 -c mingw-object.cpp
//   llvm-ar rcs mingw-object.a mingw-object.o
int initialised;

// Defined nowhere: a call the compiler cannot fold keeps the constructor a constructor
// rather than turning it into initialised data.
int read_initial_value();

struct Init {
    Init() { initialised = read_initial_value(); }
} init;

int thrower(int value) {
    if (value) throw value;
    return 0;
}

int catcher(int value) {
    try {
        return thrower(value);
    } catch (int caught) {
        return caught + initialised;
    }
}
