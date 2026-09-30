# Local formatting

Install once in each clone (Python 3.11+ and Node.js must be available):

```sh
uv tool install pre-commit==4.6.2
pre-commit install --install-hooks
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

On commit, hooks format staged source files. If files change, review them,
stage the changes and commit again. Unstaged edits are temporarily stashed by
pre-commit. No hook or formatter check is added to CI or the application build.

```sh
pre-commit run --all-files        # format all tracked, eligible files
pre-commit run --files path/to/file
```

Pinned tools: Prettier 3.9.9 (YAML/JSON/Markdown), Ruff 0.16.9 (Python,
formatting only), shfmt 3.14.1 via shfmt-py 4.2.0 (shell, including scripts
with a shell shebang and no extension). The hook environments are managed by
pre-commit; no project npm/Python dependency is added.

The target line width is 120 characters (EditorConfig, Prettier and Ruff).
It is a wrapping preference: unbreakable tokens and preserved Markdown prose
may exceed it. shfmt and Terraform retain their native wrapping behavior.

EditorConfig sets UTF-8, LF and indentation. For format-on-save, configure an
editor external tool/file watcher to run `pre-commit run --files <saved-file>`
from the repository root after saving. Pass the path as a quoted argument.
Use only one formatter per language to avoid competing styles. A formatter
rewriting the file makes pre-commit return 1; that is normal, not a parse error.

Generated/build/runtime directories, vendored packs, Terraform state and
Gradle wrapper scripts are excluded. See `.pre-commit-config.yaml` and
`.prettierignore` for the exact scope.

These configs are standalone copies of the GG2 workspace policy, generated
by `tools/formatting/sync.py` from the workspace root. After a policy update,
run that script with `--write`, then review and commit each repository's diff.

## Java and Gradle

Use JDK 25+ (the project toolchain). The repository's Gradle wrapper runs an
isolated formatting build, without configuring the application or its private
repositories. Spotless 8.10.3 uses Palantir Java Format 2.100.0, PALANTIR style
(4 spaces, 120 columns), and ktfmt 0.64 with a 120-column target for Kotlin
Gradle files. Groovy Gradle files, where present, use Greclipse 4.40 with a
120-column target. Palantir replaces google-java-format, whose 100-column
width is not configurable.

For Java `if` / `else` branches, braces are optional when the branch contains
exactly one statement. Use braces for two or more statements. This counts
statements, not physical lines. Palantir Java Format preserves existing braces
and accepts single-statement branches without them.

```java
if (player == null) return;

if (ready) {
    prepare();
    start();
}
```

```sh
python .formatting/spotless.py                 # all Java/Gradle files
python .formatting/spotless.py src/main/java/example/MyClass.java
```

The first command also includes untracked Java/Gradle sources. Prefer the
pre-commit command above when you only want tracked files.
`spotlessCheck` is available manually through `./gradlew -p .formatting
spotlessCheck`; it is never attached to the application build or CI.

## IntelliJ: nullability and explicit `this`

Close the project in IntelliJ, then install the shared settings and reopen it:

```sh
python .formatting/intellij.py
```

The installer merges local `.idea` settings and backs up previous files under
`.formatting/build/idea-backup-*`. The `GG2 Java` inspection profile highlights
unqualified instance members and nullability problems. Actions on Save uses
the separate `GG2 Cleanup` profile: its only enabled inspections add `this.`
to instance field accesses and method calls (including `Outer.this` where
appropriate). Static members and local variables are not qualified with `this`.
Use **Code > Code Cleanup**, profile **GG2 Cleanup**, to apply this to a scope.

For nullability, use **Code > Analyze Code > Infer Nullity** (Find Action also
finds it), review the proposed contracts and complete any unresolved declarations.
JetBrains `org.jetbrains.annotations.Nullable` and `NotNull` are the preferred
annotations. Inference is a deliberate IDE action, not a formatter operation or
an automatic `@NotNull` default.

The local `java-policy` pre-commit hook uses Checkstyle **14.3.0**, downloaded
with a pinned SHA-256 into `.formatting/build`. It requires exactly one JetBrains
`@Nullable` or `@NotNull` on reference fields (including static fields), method
returns, method/constructor parameters and record components. Reference arrays
and varargs count; primitives, `void`, locals, lambda parameters and implicit
record members do not. Element annotations such as `List<@NotNull String>` do
not describe the list itself. It also requires explicit instance qualifiers
where Checkstyle can resolve the member within the file; IntelliJ resolves
inherited members using the full project.

```sh
pre-commit run java-policy --files src/main/java/example/MyClass.java
python .formatting/java_policy.py  # audit all tracked Java sources
```

This check does not infer contracts or rewrite code. Existing sources have not
been mass-migrated: a Java commit can be rejected locally until the selected
files comply. The hook does not run in CI or the application build. Palantir
still controls formatting at 120 columns; no `super` policy is added.
