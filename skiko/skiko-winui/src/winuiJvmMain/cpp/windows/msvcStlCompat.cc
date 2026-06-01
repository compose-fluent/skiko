#include <cstddef>
#include <cstdint>
#include <cstring>

extern "C" {

const void* __stdcall __std_search_1(
    const void* const first,
    const void* const last,
    const void* const needle,
    const std::size_t needleCount) noexcept {
    const auto* haystackFirst = static_cast<const std::uint8_t*>(first);
    const auto* haystackLast = static_cast<const std::uint8_t*>(last);
    const auto* needleFirst = static_cast<const std::uint8_t*>(needle);

    if (needleCount == 0) {
        return first;
    }

    const auto haystackCount = static_cast<std::size_t>(haystackLast - haystackFirst);
    if (needleCount > haystackCount) {
        return last;
    }

    const auto* searchEnd = haystackLast - needleCount;
    for (const auto* candidate = haystackFirst; candidate <= searchEnd; ++candidate) {
        if (*candidate == *needleFirst && std::memcmp(candidate, needleFirst, needleCount) == 0) {
            return candidate;
        }
    }

    return last;
}

__declspec(noalias) std::size_t __stdcall __std_find_first_of_trivial_pos_1(
    const void* const haystack,
    const std::size_t haystackLength,
    const void* const needle,
    const std::size_t needleLength) noexcept {
    const auto* haystackBytes = static_cast<const std::uint8_t*>(haystack);
    const auto* needleBytes = static_cast<const std::uint8_t*>(needle);

    if (needleLength == 0) {
        return haystackLength;
    }

    for (std::size_t i = 0; i < haystackLength; ++i) {
        for (std::size_t j = 0; j < needleLength; ++j) {
            if (haystackBytes[i] == needleBytes[j]) {
                return i;
            }
        }
    }

    return haystackLength;
}

void* __stdcall __std_remove_8(void* first, void* const last, const std::uint64_t value) noexcept {
    auto* read = static_cast<std::uint64_t*>(first);
    auto* const end = static_cast<std::uint64_t*>(last);
    auto* write = read;

    for (; read != end; ++read) {
        if (*read != value) {
            *write = *read;
            ++write;
        }
    }

    return write;
}

}
