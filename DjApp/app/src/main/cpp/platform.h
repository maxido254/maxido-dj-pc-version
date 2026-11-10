// Tiny OS layer for engine.cpp: read-only file mapping, read-ahead hints, low thread priority.
// POSIX branch = Android, Linux, macOS, iOS.  Windows branch = Win32.  No other platform code lives in engine.cpp.
#pragma once
#include <cstddef>
#include <cstdint>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
static inline bool plat_map(const char *path, const void *&out, size_t &bytes) {
    wchar_t wp[1024];
    if (!MultiByteToWideChar(CP_UTF8, 0, path, -1, wp, 1024)) return false;
    HANDLE f = CreateFileW(wp, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    LARGE_INTEGER sz;
    if (!GetFileSizeEx(f, &sz) || sz.QuadPart < 8) { CloseHandle(f); return false; }
    HANDLE m = CreateFileMappingW(f, nullptr, PAGE_READONLY, 0, 0, nullptr);
    CloseHandle(f);
    if (!m) return false;
    void *p = MapViewOfFile(m, FILE_MAP_READ, 0, 0, 0);
    CloseHandle(m);                                  // the view keeps the mapping alive
    if (!p) return false;
    out = p; bytes = (size_t)sz.QuadPart;
    return true;
}
// Maps `length` bytes starting at `offset` (a multiple of 64 KiB = the Windows allocation granularity, also a multiple of every page size).
static inline bool plat_map_range(const char *path, uint64_t offset, uint64_t length, const void *&out) {
    wchar_t wp[1024];
    if (!MultiByteToWideChar(CP_UTF8, 0, path, -1, wp, 1024)) return false;
    HANDLE f = CreateFileW(wp, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    LARGE_INTEGER sz;
    if (!GetFileSizeEx(f, &sz) || length < 8 || (uint64_t)sz.QuadPart < offset + length) { CloseHandle(f); return false; }
    HANDLE m = CreateFileMappingW(f, nullptr, PAGE_READONLY, 0, 0, nullptr);
    CloseHandle(f);
    if (!m) return false;
    void *p = MapViewOfFile(m, FILE_MAP_READ, (DWORD)(offset >> 32), (DWORD)(offset & 0xFFFFFFFFu), (SIZE_T)length);
    CloseHandle(m);
    if (!p) return false;
    out = p;
    return true;
}
static inline void plat_unmap(const void *p, size_t) { if (p) UnmapViewOfFile(p); }
static inline void plat_advise(const void *, size_t) {}          // Windows cache manager reads ahead by itself
static inline void plat_lowpri() { SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_BELOW_NORMAL); }
#else
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/resource.h>
#include <fcntl.h>
#include <unistd.h>
static inline bool plat_map(const char *path, const void *&out, size_t &bytes) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return false;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 8) { close(fd); return false; }
    void *m = mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (m == MAP_FAILED) return false;
    out = m; bytes = (size_t)st.st_size;
    return true;
}
static inline bool plat_map_range(const char *path, uint64_t offset, uint64_t length, const void *&out) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return false;
    struct stat st;
    if (fstat(fd, &st) != 0 || length < 8 || (uint64_t)st.st_size < offset + length) { close(fd); return false; }
    void *m = mmap(nullptr, (size_t)length, PROT_READ, MAP_PRIVATE, fd, (off_t)offset);
    close(fd);
    if (m == MAP_FAILED) return false;
    out = m;
    return true;
}
static inline void plat_unmap(const void *p, size_t bytes) { if (p) munmap((void *)p, bytes); }
static inline void plat_advise(const void *p, size_t bytes) { madvise((void *)p, bytes, MADV_WILLNEED); }
static inline void plat_lowpri() { setpriority(PRIO_PROCESS, 0, 10); }
#endif
