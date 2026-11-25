# Contributing to Roastbook

Thanks for taking a look. Roastbook is maintained by one person in spare time, so there is no
support promise and replies can take a while.

## Issues

Bug reports, labels the app misreads, and ideas are all welcome as
[issues](../../issues/new/choose). For a misread label, describe what the bag says and what the
app filled in; please do not post photos that show anything personal. Security problems go
through [SECURITY.md](SECURITY.md), not a public issue.

## Pull requests

Pull requests are read. This public repository is a copy of a private working repository, so a
change that fits is applied there and carried back here as its own commit, credited to you in
the commit message. Small, focused changes with a test are the easiest to take. Before a larger
change, open an issue so we can agree on the shape first. There is no contributor agreement;
contributions are accepted under the [Apache License 2.0](LICENSE).

## Building and testing

You need JDK 21 and the Android SDK with platform 37 (licenses accepted with
`sdkmanager --licenses`). Point Gradle at the SDK with `ANDROID_HOME` or `sdk.dir` in
`local.properties`.

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest createDebugUnitTestCoverageReport lintDebug
python3 scripts/coverage.py --min 95
```

CI runs the same commands on every pull request. Line coverage below 95% fails the build, and
so does any compiler or lint warning, in main or test code. `scripts/coverage.py` names the few
SDK adapters it leaves out of the coverage count, and why. The few lint checks that are off are
listed in `app/lint.xml` with the reason, and a suppression in code carries its reason beside it.

## What the code holds to

- Every extracted field is a suggestion. A read never overwrites a field the person edited, and
  each coffee records which tier read it.
- Nothing leaves the device unless the person acts: no account, no analytics, no server of ours.
  A new network call needs a reason a user would agree with, and a line in the privacy policy.
- All user-facing text lives in `strings.xml`.
- Tests read as behavior: a test's name says what the app does. Fixtures use invented roasters
  and coffees, never text copied from a real bag, real accounts or personal data.
- Comments say why, for a stranger reading the code later.
- A refactor changes no behavior and lands apart from fixes.
