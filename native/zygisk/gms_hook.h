#pragma once

#include "kuroko_shm.h"

#include <jni.h>
#include <cstdint>
#include <string>

namespace kuroko {

// Install process-scoped GMS FusedLocationProvider (FLP) Binder vtable hooks inside
// com.google.android.gms or com.google.android.gms.persistent ONLY.
//
// Invariants:
// - Zero dlopen/dlsym/dlerror/dlclose imports (pure ELF64 .dynsym/.dynstr + /proc/self/maps).
// - Zero inline hooks (pure PT_GNU_RELRO atomic vtable pointer swap, 16KB + BTI/PAC safe).
// - Zero background threads spawned here (safe to invoke in preAppSpecialize before unshare).
//
// Writes a compact status string ("ok:vtable:..." or "err:...") to out_status if non-null.
bool install_gms_hooks(JNIEnv* env, const KurokoShm* shm, const std::string& proc_name,
                       std::string* out_status = nullptr, uint32_t self_uid = 0);

// Start the Kuroko-1Hz-GMS active indoor pump daemon thread.
// Must be invoked in postAppSpecialize (after Zygote SpecializeCommon unshare completes).
void start_gms_pump_thread();

} // namespace kuroko
