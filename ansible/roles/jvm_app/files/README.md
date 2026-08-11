Drop the CI-built, versioned jar here as `game-service-<app_version>.jar`
(matching the `app_version` var in inventory/group_vars/all.yml) before
running the `jvm_app` role. In the full pipeline this file lands here
automatically as the deploy step of your GitHub Actions workflow.
