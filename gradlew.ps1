$ErrorActionPreference = 'Stop'
# Native PowerShell entry point to the same Gradle wrapper; no cmd.exe / Bash hop.
& java '-Xmx64m' '-Xms64m' '-Dorg.gradle.appname=gradlew' '-jar' "$PSScriptRoot/gradle/wrapper/gradle-wrapper.jar" @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
