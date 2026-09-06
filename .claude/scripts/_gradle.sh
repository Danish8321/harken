# Sourced by the gate scripts. The Gradle entry point is not the same file on every
# machine: Git Bash on Windows cannot execute the POSIX `gradlew`, and Linux and macOS
# have no `gradlew.bat`. Hard-coding either one made the gates run on one OS only, which
# is why CI could not run them at all.
# Absolute, so a script can still find its siblings after it cds to the repo root.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) GRADLEW=./gradlew.bat ;;
  *)                    GRADLEW=./gradlew ;;
esac
