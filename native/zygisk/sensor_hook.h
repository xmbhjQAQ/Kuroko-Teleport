#pragma once

#include "kuroko_shm.h"
#include "zygisk.hpp"

#include <jni.h>
#include <string>

namespace kuroko {

// Install pure PLT/GOT hooks on /system/lib64/libsensorservice.so inside system_server
// (primary via java.lang.System.load + Zygisk PLT; self-healing fallback via ELF64 .rela.plt GOT patcher)
// and start the active step-event injector + maps watchdog thread.
// Writes a compact status summary (e.g. "ok:jni_plt:core=5/5,disc=3/3", "wait:defer_got", or "err:...")
// to out_status if non-null.
bool install_sensor_hooks(zygisk::Api* api, JNIEnv* env, const KurokoShm* shm, std::string* out_status = nullptr);

} // namespace kuroko
