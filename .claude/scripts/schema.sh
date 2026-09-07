#!/usr/bin/env bash
# Schema gate: the only route for a change to the on-device database (ARC-015).
#
# A schema change is the one edit in this repository that can destroy something the user
# cannot get back. The audio path, the title and the tags exist nowhere but the phone, and
# a migration Room generates for a rename is a drop-and-add: it runs clean, it validates
# clean, and every row's value for that column is gone.
#
# So this script does not apply anything. It rebuilds the schema Room exports from the
# entities as they are written right now, shows what changed against the schema committed
# for that version, and says what must be true before the change can ship. Reading the
# output is the point; there is nothing to approve and no --yes.
#
# Usage: bash .claude/scripts/schema.sh
set -euo pipefail
# Sourced before the cd, while "$0" still points where the caller found it.
# shellcheck source=_gradle.sh
. "$(dirname "$0")/_gradle.sh"
cd "$(dirname "$0")/../.."

SCHEMA_DIR="src/Harken.Android/app/schemas/com.harken.android.data.local.HarkenDatabase"

if [ ! -d "$SCHEMA_DIR" ]; then
  echo "No exported schemas at $SCHEMA_DIR." >&2
  echo "exportSchema must be true and room.schemaLocation must be set (ARC-039)." >&2
  exit 1
fi

# Any uncommitted change under the schema directory would be indistinguishable from what
# this run produces, and the whole comparison is against what is committed.
if ! git diff --quiet -- "$SCHEMA_DIR" || ! git diff --cached --quiet -- "$SCHEMA_DIR"; then
  echo "The exported schemas have uncommitted changes." >&2
  echo "Commit or stash them first: this script compares what the entities produce now" >&2
  echo "against what is committed, and cannot tell the two apart otherwise." >&2
  git status --short -- "$SCHEMA_DIR" >&2
  exit 1
fi

echo "== schema: gradle kspDebugKotlin (re-exporting from the entities) =="
(cd src/Harken.Android && "$GRADLEW" kspDebugKotlin)

echo
echo "== schema: what changed =="
if git diff --quiet -- "$SCHEMA_DIR"; then
  echo "Nothing. The entities produce the schema that is committed."
  echo
  echo "If you meant to change the schema, the edit has not landed in an @Entity yet."
  exit 0
fi

git --no-pager diff --stat -- "$SCHEMA_DIR"
echo
git --no-pager diff -- "$SCHEMA_DIR"

# Room writes the current version's file every build. If that file changed, the entities
# no longer describe the version they are numbered as, and an install on the old shape has
# no migration to reach the new one — Room will refuse to open it. A change that only adds
# a new N.json is the correct shape.
modified=$(git diff --name-only --diff-filter=M -- "$SCHEMA_DIR")
if [ -n "$modified" ]; then
  echo
  echo "!! A schema already committed for a shipped version changed:" >&2
  echo "$modified" | sed 's/^/     /' >&2
  echo "   Bump the @Database version so Room writes a new file, and leave the old one" >&2
  echo "   exactly as it is. A version whose shape changed under it is a database Room" >&2
  echo "   will refuse to open on every device that already has the old one." >&2
fi

echo
echo "== schema: before this ships =="
cat <<'NOTES'
1. A rename must be expressed as a rename.

   Room's own generated migration for a renamed column is a drop and an add. It
   passes every automated check in this repository and silently empties the column
   for every existing install. Write the migration by hand:

       ALTER TABLE sessions RENAME COLUMN oldName TO newName

   SQLite has supported RENAME COLUMN since 3.25 (Android 11 / API 30). Below that
   it is a create-copy-drop-rename of the whole table, and the copy must name every
   column, so read the diff above and write out all of them.

2. The database version must be bumped, and the new schema committed.

   Every file already in the schema directory is the record of what a shipped
   version looked like. Never edit one: MigrationTestHelper builds a real database
   from it, so editing it makes the test agree with the migration and disagree with
   the phone.

3. SessionDatabaseMigrationTest must assert this migration, and must pass.

       bash .claude/scripts/test-full.sh

   It needs a phone attached. There is no way to prove a migration preserves rows
   without running it against a real SQLite, and no gate here that does not.
NOTES
echo
echo "== schema: reviewed, not applied =="
