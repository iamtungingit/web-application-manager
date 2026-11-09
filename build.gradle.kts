// Web Application Manager (WAM) — root build script.
// Toolchain versions are pinned in gradle/libs.versions.toml and documented in docs/ARCHITECTURE.md.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
