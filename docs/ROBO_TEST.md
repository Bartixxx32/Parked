# Firebase Robo test

Robo test is Firebase Test Lab's automatic UI crawler. Google runs the debug
APK on virtual or physical Android devices, taps through the screens, and
reports crashes, ANRs, screenshots, video and logcat. It is **not** Robolectric
(which runs JVM tests) and it does not replace the deterministic screenshot
workflow.

The workflow is `.github/workflows/robo.yml`. It is started manually and only
builds the debug APK; it never publishes a release.

## One-time Google Cloud setup

Test Lab needs a Firebase project with billing enabled. The simplest setup is
the Blaze plan; Test Lab has a limited free quota and then bills per
device-minute.

```bash
PROJECT="your-project-id"
SA="parked-robo"
SA_EMAIL="$SA@$PROJECT.iam.gserviceaccount.com"

gcloud config set project "$PROJECT"
gcloud services enable testing.googleapis.com

gcloud iam service-accounts create "$SA" \
  --display-name="Parked Robo test"

gcloud projects add-iam-policy-binding "$PROJECT" \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/firebase.testlab.admin"
```

Optional but recommended: keep the reports in a bucket so CI can upload them as
an artifact.

```bash
BUCKET="gs://$PROJECT-parked-robo"

gcloud storage buckets create "$BUCKET" --location=europe-central2
gcloud storage buckets add-iam-policy-binding "$BUCKET" \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/storage.objectAdmin"
```

Create a key for the service account and add it to GitHub:

```bash
gcloud iam service-accounts keys create parked-robo.json \
  --iam-account="$SA_EMAIL"
```

Repository **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `GCP_PROJECT_ID` | the project id |
| `GCP_SA_KEY` | full contents of `parked-robo.json` |
| `GCP_RESULTS_BUCKET` | optional, for example `gs://your-project-parked-robo` |

Delete the local JSON after adding the secret. The key is a long-lived
credential; rotate it if it leaks. For a long-term setup, Workload Identity
Federation is safer than a service-account key.

## Running

1. Open **Actions → Robo test → Run workflow**.
2. Leave `ref` as `main` or choose a tag/branch.
3. Adjust `devices` if needed. The default is two Arm virtual devices with
   Polish and English locales:

   ```text
   model=MediumPhone.arm,version=35,locale=pl_PL,orientation=portrait
   model=MediumPhone.arm,version=35,locale=en_US,orientation=portrait
   ```

   List what is currently available with:

   ```bash
   gcloud firebase test android models list --filter=virtual
   ```

   Remove the filter to see physical devices. Physical devices are more
   realistic but have smaller capacity and a shorter maximum test duration.
4. Set `timeout` (default `5m`) and start the run.

The log contains the Firebase console link. If `GCP_RESULTS_BUCKET` is set, the
workflow also uploads screenshots, video and logs as the
`robo-test-results` artifact.

## Robo scripts

A plain crawl may stop on onboarding, a permission dialog or a login screen. A
Robo script records a fixed path, for example: accept the intro, grant location,
open History, add a spot.

Record it in Android Studio (**Tools → Firebase → Test Lab → Record Robo
Script**), commit the JSON file, and pass its repository path in the
`robo_script` input, for example `scripts/robo/onboarding.json`.

Robo scripts can also enter text into `EditText` fields and click elements by
resource id. Do not put real family invite links, tokens or photos in a script.

## What it can and cannot catch

Good for: startup crashes, missing activities, broken navigation, ANRs,
permission-flow problems and OEM/API-level differences.

Not good for: asserting encryption details, family sync state or exact UI text.
Those stay in JVM/Robolectric tests and the manual phone checklist. Store
screenshots still come from `.github/workflows/screenshots.yml`.
