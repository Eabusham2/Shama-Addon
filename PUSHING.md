# Getting this onto GitHub

I cannot push, commit or merge for you — there is no git repo in my environment and no
credentials for yours. These are the exact commands to run on your own machine. Everything
below puts the whole project on **one branch, `main`**, which is what the workflow builds
and releases from.

Replace `USER` with your GitHub username throughout.

---

## If the repo does not exist yet

```bash
cd C:\Users\eabus\Downloads\Shama-addon

git init
git branch -M main
git add .
git commit -m "Shama addon"
git remote add origin https://github.com/USER/Shama-addon.git
git push -u origin main
```

## If the repo exists and is 2 months stale

The simplest thing, since this folder is the truth and the remote is old:

```bash
cd C:\Users\eabus\Downloads\Shama-addon

git init                 # skip if there is already a .git folder here
git branch -M main
git remote add origin https://github.com/USER/Shama-addon.git   # skip if origin exists
git add .
git commit -m "Update addon"
git push --force origin main
```

`--force` replaces whatever is on the remote with what you have locally. That is what you
want here, because the remote is stale and this folder is current. It does throw away the
old remote history, so if there is anything on GitHub you still need, pull it first.

## Merging other branches into main

If there are stray branches you want folded in:

```bash
git checkout main
git merge --no-ff other-branch -m "Merge other-branch into main"
git push origin main
```

Then delete the branch so everything stays on one:

```bash
git push origin --delete other-branch
git branch -d other-branch
```

---

## One-time settings on GitHub

**Settings → Actions → General → Workflow permissions** → pick
**"Read and write permissions"**. Without this the release step cannot publish and the run
fails at the end with a 403, even though the build itself passed.

---

## What happens after you push

1. **Precheck** runs first — a few seconds, no Java. It runs `tools/check_sources.py` and
   confirms the workflow file itself parses. A broken commit fails here, before Gradle is
   ever started.
2. **Gradle build** runs only if precheck passed (`needs: precheck`).
3. **Release** happens only if the build produced a jar. Both release steps sit inside the
   Gradle job, so a release can only ever come from a green build.

Pushing to `main` replaces the release tagged `latest`, so this link is always the newest
build and never changes:

```
https://github.com/USER/Shama-addon/releases/latest
```

Pushing a tag makes its own permanent release instead, so old versions stay downloadable:

```bash
git tag v1.0
git push origin v1.0
```

---

## Checking it went green

Go to `https://github.com/USER/Shama-addon/actions`. A green tick on the newest run means
precheck and the build both passed and the release was published. Open the run and the
summary at the top has the download link.

The badge in the README shows the same thing, once you replace `USER` in it.

---

## Honest note on what I verified

I ran the source checks and confirmed the workflow YAML parses. I could not run the
workflow itself, and I could not compile the Java — there is no Minecraft or Gradle
environment here. **The first real test of both is your first push.** If the Gradle step
fails, send me the log and I will fix it.
