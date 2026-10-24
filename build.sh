#!/bin/sh
# Builds from Code On The Go's terminal, which is needed while its build button crashes the IDE.
# The aapt2 Gradle downloads runs only on x86 PCs, so this uses the phone's copy from the IDE's SDK.
#
#   sh build.sh              debug APK
#   sh build.sh release      release APK
#   sh build.sh test         run the unit tests
#   sh build.sh lint         check the code for hints, one report per module in build/lint/
#   sh build.sh clean        delete build output
#   sh build.sh stop         stop the Gradle daemon
#   sh build.sh <tasks...>   any other Gradle tasks
#
# The full output goes to build-log.txt; the end of it is shown if the build fails.

cd "$(dirname "$0")" || exit 1

AAPT2=$(ls "$HOME"/android-sdk/build-tools/*/aapt2 2>/dev/null | tail -1)
[ -n "$AAPT2" ] || AAPT2=$(find "$HOME" -name aapt2 -type f -path '*build-tools*' 2>/dev/null | tail -1)
if [ -z "$AAPT2" ]; then
    echo "Can't find the IDE's aapt2 under $HOME."
    exit 1
fi

mode=$1
case "$1" in
    "") set -- assembleDebug ;;
    release) set -- assembleRelease ;;
    test) set -- testDebugUnitTest ;;
    lint) rm -rf build/lint; set -- lintDebug ;;
    stop) exec sh gradlew --stop ;;
esac

echo "Running: $*  (full output in build-log.txt)"
sh gradlew "$@" --console=plain -Pandroid.aapt2FromMavenOverride="$AAPT2" > build-log.txt 2>&1
status=$?

if [ $status -eq 0 ] && [ "$mode" = test ]; then
    echo "All tests passed."
elif [ $status -eq 0 ] && [ "$mode" = lint ]; then
    # Each report ends with "N errors, N warnings" (or "No issues found.").
    for report in build/lint/*.txt; do
        echo "$(basename "$report" .txt): $(tail -1 "$report")"
    done
    echo "Read a report with: less build/lint/<module>.txt"
elif [ $status -eq 0 ]; then
    echo "Build succeeded."
    ls app/build/outputs/apk/*/*.apk 2>/dev/null
elif [ "$mode" = test ]; then
    # The failing tests and why, without the rest of Gradle's output.
    grep -E "FAILED|Exception|Error|expected" build-log.txt | head -40
    echo "Tests failed (exit $status). Full reports: <module>/build/reports/tests/testDebugUnitTest/index.html"
else
    tail -40 build-log.txt
    echo "Build failed (exit $status)."
fi
exit $status
