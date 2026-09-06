# smoke-test

Proves `ipie-build-conventions` works **from a published artifact**, in a project that has no
`ipie-quality-config` folder of its own — the situation of a service that has left this monorepo.

## Why this exists

`ipie.java-conventions` resolves the Checkstyle and SpotBugs configuration two ways: from the
top-level `ipie-quality-config/` folder when building inside this repository, and by extracting the
copy packaged into the plugin jar when building outside it. **Every build inside this repository
takes the first path**, so the second is otherwise entirely untested — and it is the path every
extracted service depends on.

It also guards against a subtler failure. If the packaged configuration fails to load, Checkstyle
runs with no rules and passes silently, which is indistinguishable from success. So this test does
not merely assert that the build succeeds: it asserts that a deliberate violation is **rejected**.

## Two subprojects, because the convention plugins fail differently

- **`library/`** applies `ipie.java-conventions`, which has no project dependencies of its own.
- **`service/`** applies `ipie.spring-service-conventions` — the plugin a *real* service applies. It
  carries the platform BOM and the shared test-fixtures wiring **inside the plugin**, and those were
  unconditional `project(':ipie-parent')` references until 2026-08-09.

That distinction is the reason `service/` exists. The first version of this test covered only
`library/`, so it passed while `ipie.spring-service-conventions` was still unusable outside the
monorepo. The coupling was found by actually extracting `ipie-communication-service`, not by this
test — it was proving the wrong plugin.

## What it checks

1. `ipie.java-conventions` — a clean class passes `checkstyleMain` and `spotbugsMain`, with the
   configuration extracted from the jar into `library/build/ipie-quality-config/`.
2. `ipie.spring-service-conventions` — compiles and runs Checkstyle against a class that touches a
   type from the published `ipie-common-libs`, so the BOM, the shared library and the quality gates
   all resolve from artifacts rather than projects.
3. A 168-character line is rejected by the platform's own `LineLength` rule.

Check 2 is verified to fail as intended: reintroducing `project(':ipie-parent')` in the plugin makes
this test fail with `Project with path ':ipie-parent' could not be found`. Because Gradle configures
every subproject up front, that failure actually surfaces during check 1 — the build never reaches
check 2. The signal is the error, not which step reports it.

## Running it

    ./smoke-test/run.sh

It publishes the current `ipie-build-conventions` and `ipie-parent` to Maven Local first, so it
always tests the working tree rather than whatever was published last.
