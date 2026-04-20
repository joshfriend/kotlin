# kotlin-npm-tooling

This directory is used to manage npm dependencies required by Kotlin Gradle plugin
for building, testing, and running Kotlin JS and WasmJS projects.

The `package.json` file in this directory is used as the single-source-of-truth
for KGP's npm tooling dependencies.
A single directory is used to help tools (like Dependabot) upgrade the dependencies.

npm dependencies are not installed directly here.
Instead, Gradle tasks use `package.json` to generate lockfiles (which are bundled into KGP)
and Kotlin files for accessing the dependencies.

### Maintenance

The versions must be kept up to date periodically, or in case of security issues.

To upgrade, run `./gradlew :kotlin-gradle-plugin:upgradeKgpNpmToolingVersions`.
