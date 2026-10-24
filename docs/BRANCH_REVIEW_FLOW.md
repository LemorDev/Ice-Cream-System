# Branch and review flow for the first release

Verified 5 October 2026. Repository: [LemorDev/Ice-Cream-System](https://github.com/LemorDev/Ice-Cream-System). This is branch and deployment control evidence for [R04](FIRST_RELEASE_TASKS.md), not approval to merge or deploy.

## Branches and PR

| Ref | Verified commit | Purpose |
| --- | --- | --- |
| `main` / `origin/main` | `c90835f` | Reviewed starting baseline; future production releases only |
| `develop` / `origin/develop` | `c90835f` | Created from the freshly fetched `origin/main`; development integration |
| `codex/inventory-recipes-ui` | `65284df` when [draft PR #1](https://github.com/LemorDev/Ice-Cream-System/pull/1) opened; subsequent R04 check commits are on the same branch | First-release feature work; PR base is `develop` |

`main` is an ancestor of the feature branch. The feature PR was verified as `codex/inventory-recipes-ui` → `develop`, with the expected base and head commits. Do not merge the draft until its required checks and review gates pass. Feature PRs may be squash-merged into `develop`; promote an accepted candidate from `develop` into `main` with a separate release PR and a merge commit so the two long-lived branches retain shared ancestry.

## GitHub controls

The repository was made public at the user's request to enable branch protection on the current GitHub plan. A history scan found no private-key or service-role token patterns; old example files contain one Supabase `anon` JWT, which is a client-publishable key. Publishing also exposes the repository's commit-author email and previous commits.

Both `develop` and `main` have branch protection enabled and enforced for administrators: a PR is required, force pushes and branch deletion are disabled, and review conversations must be resolved. The approving-review count is zero because a sole maintainer cannot approve their own PR; obtain a second person's review when one is available. Both rules require the exact GitHub job names `Web checks` and `Android checks`, with the PR branch up to date before merge. The [PR checks workflow](../.github/workflows/pr-checks.yml) runs web lint/tests/development build and Android development unit tests/build using inert CI endpoints. [R07](FIRST_RELEASE_TASKS.md) expands CI with isolated database checks and tighter toolchain pinning; add its database check to both rules before merging a release candidate.

The first run exposed a removed Android SDK `tools` package in the older setup action; the workflow now uses the current action. On commit `d39525e`, both required jobs completed successfully in [GitHub Actions run 37280302762](https://github.com/LemorDev/Ice-Cream-System/actions/runs/37280302762). Verify them again after each new PR commit; the branch rules require a fresh passing result.

## Deployment trigger

The user reported that no web hosting project is connected yet. GitHub reported zero Actions workflows before this task. The new [PR checks workflow](../.github/workflows/pr-checks.yml) runs only on PRs into `develop` or `main`, or by manual dispatch; it contains **no deployment job**. Therefore, publishing these branches does not currently deploy the IMS or POS. Supabase migrations also remain manual and require the project check and dry run in R08/R19.

When R10/R17 create the two Cloudflare Pages projects, connect `develop` only to the **development** project and `main` only to the **production** project. Keep production automatic deployment disabled until the approved R20 release action. Verify the Pages branch setting and actual deployed commit before changing this statement. The POS APK is built and installed separately; a Git push does not distribute it.

## Required review sequence

1. Open feature PR into `develop`; verify base/head, diff, and CI result. Do not use the GitHub default `main` base for feature work.
2. Complete R05/R06 risk audit and fixes, R07 CI, R08 database contract testing, and R09 physical-phone tests. Re-run checks after each fix.
3. Merge to `develop` only when the PR is ready. R10–R12 then use development hosting and the `.dev` POS for acceptance.
4. Freeze the tested commit in R13. Open a release PR from `develop` to `main`; verify required checks and UAT sign-off before merging. Production deployment remains a separate R19/R20 action.
