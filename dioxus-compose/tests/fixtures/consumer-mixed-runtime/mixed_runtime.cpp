// A C++ library built with the C runtime DLL (/MD), standing for any C++ dependency an
// application links that was built with the defaults. It uses the C++ standard library, so
// its objects carry the runtime guard and name the DLL runtime's libraries.
#include <cstddef>
#include <cstring>
#include <string>

extern "C" std::size_t mixed_runtime_greeting(char *out, std::size_t capacity) {
    std::string text = "built for the C runtime DLL";
    text += ", linked into one executable";
    std::size_t length = text.size() < capacity ? text.size() : capacity - 1;
    std::memcpy(out, text.data(), length);
    out[length] = '\0';
    return length;
}
