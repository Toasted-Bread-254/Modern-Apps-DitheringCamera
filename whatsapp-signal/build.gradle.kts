// :whatsapp-signal — provides the classic pure-Java Signal protocol
// (org.whispersystems.libsignal.*, X3DH) that the WhatsApp bridge needs.
// libsignal-android 0.86 (used by the Signal bridge) dropped X3DH, which
// WhatsApp companion sessions require, so this artifact coexists under a
// different package. Its bundled protobuf is relocated to
// com.vayunmathur.messages.shadedproto so it does not collide with the
// app's protobuf 4.x.
//
// NOTE: the module's original source + shadow build were never committed
// to VCS and were lost; only the prebuilt shaded jar remained. This file
// re-exposes that byte-identical artifact via the "shaded" configuration
// so :messages configures and links against the exact same classes. The
// module source / shadow build should be properly restored later (see the
// team note from integrator); until then the vendored jar is the source
// of truth.

configurations.create("shaded") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add("shaded", file("libs/whatsapp-signal-shaded.jar"))
}
