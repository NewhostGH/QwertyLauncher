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
# While it runs, a progress bar shows how far along it is and the time left, measured against
# the last successful run of the same kind (kept in .gradle/build-sh/). The first run of each
# kind has nothing to measure against, so it shows the time so far and the current task.

cd "$(dirname "$0")" || exit 1

AAPT2=$(ls "$HOME"/android-sdk/build-tools/*/aapt2 2>/dev/null | tail -1)
[ -n "$AAPT2" ] || AAPT2=$(find "$HOME" -name aapt2 -type f -path '*build-tools*' 2>/dev/null | tail -1)
if [ -z "$AAPT2" ]; then
    echo "Can't find the IDE's aapt2 under $HOME."
    exit 1
fi

mode=$1
# Only the app's APK: the features are bundled as module zips built from their release
# variant (packModule), so building their own debug and release AARs too only doubles the work.
case "$1" in
    "") set -- :app:assembleDebug ;;
    release) set -- :app:assembleRelease ;;
    test) set -- testDebugUnitTest ;;
    lint) rm -rf build/lint; set -- lintDebug ;;
    stop) exec sh gradlew --stop ;;
esac

state=.gradle/build-sh
mkdir -p "$state"
kind=$(printf '%s' "$*" | tr -c 'A-Za-z0-9' '_' | cut -c1-60)
# One line per Gradle task: the second it started and its name; then the total on a last line.
history="$state/$kind.times"
current="$state/$kind.new"
: > "$current"

# m:ss
clock() {
    printf '%d:%02d' $(($1 / 60)) $(($1 % 60))
}

# The terminal's width. Asks the terminal itself: tput inside $(...) sees a pipe and says 80.
columns() {
    cols=$(stty size 2>/dev/null < /dev/tty | cut -d' ' -f2)
    [ -n "$cols" ] && [ "$cols" -gt 0 ] 2>/dev/null || cols=${COLUMNS:-60}
    echo "$cols"
}

# Copies Gradle's output through, noting when each task starts.
record() {
    while IFS= read -r line; do
        printf '%s\n' "$line"
        case $line in
            "> Task "*)
                task=${line#> Task }
                echo "$(($(date +%s) - start)) ${task%% *}" >> "$current" ;;
        esac
    done
}

# Redraws the progress line once a second.
ticker() {
    cols=$(columns)
    width=$((cols - 1))
    if [ "$cols" -ge 60 ]; then bar_width=20; else bar_width=10; fi
    total= tasks=0 shown=0
    if [ -s "$history" ]; then
        total=$(tail -1 "$history" | cut -d' ' -f1)
        tasks=$(($(wc -l < "$history") - 1))
    fi
    while :; do
        now=$(($(date +%s) - start))
        n=$(($(wc -l < "$current")))
        if [ "$n" -eq 0 ]; then
            started=0 task="starting Gradle"
        else
            last=$(tail -1 "$current")
            started=${last%% *} task=${last#* }
        fi
        if [ -n "$total" ] && [ "$total" -gt 0 ]; then
            # When this task started last time, scaled by how fast this run is going.
            if [ "$n" -eq 0 ]; then was=0; else was=$(sed -n "${n}p" "$history" | cut -d' ' -f1); fi
            if [ "$n" -gt "$tasks" ] || [ -z "$was" ]; then
                left=0
            else
                rest=$((total - was))
                [ "$was" -ge 5 ] && [ "$started" -gt 0 ] && rest=$((rest * started / was))
                left=$((rest - (now - started)))
            fi
            if [ "$left" -gt 0 ]; then
                pct=$((now * 100 / (now + left)))
                eta="$(clock "$left") left"
            else
                pct=99 eta="almost done"
            fi
            [ "$pct" -gt 99 ] && pct=99
            # The estimate shifts as the run speeds up or slows down; the bar only moves forward.
            [ "$pct" -lt "$shown" ] && pct=$shown
            shown=$pct
            filled=$((pct * bar_width / 100)) bar=
            i=0
            while [ $i -lt $bar_width ]; do
                if [ $i -lt $filled ]; then bar="$bar#"; else bar="$bar-"; fi
                i=$((i + 1))
            done
            text="[$bar] $pct% $eta  $task"
        else
            text="$(clock "$now")  task $n  $task  (first run, no estimate yet)"
        fi
        printf "\r%-${width}.${width}s" "$text"
        sleep 1
    done
}

echo "Running: $*  (full output in build-log.txt)"
start=$(date +%s)
ticker_pid=
if [ -t 1 ]; then
    ticker &
    ticker_pid=$!
    trap 'kill $ticker_pid 2>/dev/null' EXIT
    trap 'exit 130' INT TERM
fi
{
    sh gradlew "$@" --console=plain -Pandroid.aapt2FromMavenOverride="$AAPT2" 2>&1
    echo $? > "$state/status"
} | record > build-log.txt
status=$(cat "$state/status")
if [ -n "$ticker_pid" ]; then
    kill "$ticker_pid" 2>/dev/null
    wait "$ticker_pid" 2>/dev/null
    # Clear the progress line.
    printf '\r%*s\r' "$(($(columns) - 1))" ''
fi
elapsed=$(($(date +%s) - start))
# Successful runs are what the next estimate is measured against.
if [ "$status" -eq 0 ]; then
    echo "$elapsed total" >> "$current"
    mv "$current" "$history"
else
    rm -f "$current"
fi
echo "Took $(clock "$elapsed")."

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
