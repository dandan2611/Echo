#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Usage: $0 <major|minor|patch>"
    echo ""
    echo "Bumps the project version in all build.gradle.kts files."
    echo "Examples:"
    echo "  $0 major   # 5.1.0 -> 6.0.0"
    echo "  $0 minor   # 5.1.0 -> 5.2.0"
    echo "  $0 patch   # 5.1.0 -> 5.1.1"
    exit 1
}

if [[ $# -ne 1 ]]; then
    usage
fi

BUMP_TYPE="$1"

if [[ "$BUMP_TYPE" != "major" && "$BUMP_TYPE" != "minor" && "$BUMP_TYPE" != "patch" ]]; then
    echo "Error: invalid bump type '$BUMP_TYPE'"
    usage
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT_BUILD="$ROOT_DIR/build.gradle.kts"

CURRENT_VERSION=$(grep '^version = ' "$ROOT_BUILD" | sed 's/.*"\(.*\)"/\1/')

if [[ -z "$CURRENT_VERSION" ]]; then
    echo "Error: could not read current version from $ROOT_BUILD"
    exit 1
fi

IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"

case "$BUMP_TYPE" in
    major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
    minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
    patch) PATCH=$((PATCH + 1)) ;;
esac

NEW_VERSION="$MAJOR.$MINOR.$PATCH"

BUILD_FILES=(
    "$ROOT_DIR/build.gradle.kts"
    "$ROOT_DIR/api/build.gradle.kts"
    "$ROOT_DIR/core/build.gradle.kts"
    "$ROOT_DIR/paper/build.gradle.kts"
    "$ROOT_DIR/velocity/build.gradle.kts"
)

for file in "${BUILD_FILES[@]}"; do
    if [[ -f "$file" ]]; then
        sed -i'' -e "s/version = \"$CURRENT_VERSION\"/version = \"$NEW_VERSION\"/" "$file"
    else
        echo "Warning: $file not found, skipping"
    fi
done

echo "$CURRENT_VERSION -> $NEW_VERSION"
