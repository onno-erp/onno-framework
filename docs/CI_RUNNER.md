# Self-hosted CI runner

Every workflow job declares `runs-on: ${{ vars.CI_RUNNER || 'ubuntu-latest' }}`. With the
`CI_RUNNER` repository variable unset, jobs run on GitHub-hosted runners. Set it to a self-hosted
runner's label to move all CI and release jobs there, for example when GitHub-hosted minutes are
unavailable:

```bash
gh variable set CI_RUNNER --body onno-ci     # use the self-hosted runner
gh variable delete CI_RUNNER                 # back to GitHub-hosted
```

## Pull requests

`CI` runs on pushes to `main` and on release tags, not on pull requests. Verify a branch on request:

```bash
gh workflow run CI --ref feat/my-branch
gh run watch
```

## The runner

`onno-ci-1` (label `onno-ci`) is a repository-level runner. It runs as the unprivileged `runner`
user from `/home/runner/actions-runner`, as the systemd service
`actions.runner.onno-erp-onno-framework.onno-ci-1`.

```bash
systemctl status 'actions.runner.*'          # is it up
journalctl -u 'actions.runner.*' -f          # its log
cd /home/runner/actions-runner && ./svc.sh stop|start
```

Setting up another one: create a `runner` user, unpack the
[actions/runner](https://github.com/actions/runner/releases) release into its home, run
`./bin/installdependencies.sh`, then `./config.sh --url https://github.com/onno-erp/onno-framework
--token <registration token> --labels onno-ci` as that user and `./svc.sh install runner && ./svc.sh start`
as root. A registration token comes from
`gh api -X POST repos/onno-erp/onno-framework/actions/runners/registration-token`.

## What the host needs

- `git`, `curl`, `tar`, `unzip`, `zip`, `jq`. Java and Node are installed per job by `setup-java` and
  `setup-node`.
- Docker, for the PostgreSQL integration tests (`@Testcontainers(disabledWithoutDocker = true)`):
  without it they are skipped, and the build still passes.
- Room for a full `./gradlew clean check publishToMavenLocal aggregateJavadoc` plus the Vite builds:
  plan for 4 vCPU, 8 GB RAM and 30 GB of disk. On a smaller host, cap Gradle in the runner user's
  `~/.gradle/gradle.properties` (`org.gradle.jvmargs`, `org.gradle.workers.max=1`,
  `org.gradle.parallel=false`) and add swap; builds then take much longer.

## Security

A self-hosted runner executes the workflow of every pull request it is given, with the runner
user's access to the host, and release jobs receive the publishing secrets there. Keep the
repository private or restrict who can open pull requests, never run the runner as root, and do not
share the host with unrelated services.
