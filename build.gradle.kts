// Shared module configuration lives in build-logic convention plugins (ADR-0012).

plugins {
    id("com.diffplug.spotless") version "8.10.2"
}

val ktlintVersion = "1.8.0"

// Rules disabled after a trial on this codebase (docs/TESTING.md §6): they forced braces on every when branch and
// expression re-wrapping that made code longer without adding clarity.
val ktlintRules =
    mapOf(
        "ktlint_code_style" to "intellij_idea",
        "max_line_length" to "140",
        "ktlint_standard_multiline-expression-wrapping" to "disabled",
        "ktlint_standard_when-entry-bracing" to "disabled",
        "ktlint_standard_blank-line-between-when-conditions" to "disabled",
        "ktlint_standard_chain-method-continuation" to "disabled",
        "ktlint_standard_function-literal" to "disabled",
        "ktlint_standard_string-template-indent" to "disabled",
    )

spotless {
    kotlin {
        target("shared/*/src/**/*.kt", "build-logic/src/**/*.kt")
        ktlint(ktlintVersion).editorConfigOverride(ktlintRules)
    }
    kotlinGradle {
        target("*.gradle.kts", "shared/*/*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintRules)
    }
}
